package com.nik.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nik.domain.BookImportSourceType;
import com.nik.domain.BookImportStatus;
import com.nik.exception.BookException;
import com.nik.exception.InvalidRequestException;
import com.nik.model.Genre;
import com.nik.payload.dto.BookImportJobDTO;
import com.nik.payload.dto.BookImportRecordDTO;
import com.nik.payload.request.BookImportRequest;
import com.nik.repository.BookRepository;
import com.nik.repository.GenreRepository;
import com.nik.service.BookImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookImportServiceImpl implements BookImportService {

    private static final String UPSERT_SQL = """
        INSERT INTO books (
            isbn, title, author, genre_id, publisher, publication_date, language, pages, description,
            total_copies, available_copies, price, cover_image_url, dewey_decimal, library_of_congress_code,
            call_number, audience_level, subject_headings, active, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (isbn) DO UPDATE SET
            title = EXCLUDED.title,
            author = EXCLUDED.author,
            genre_id = EXCLUDED.genre_id,
            publisher = EXCLUDED.publisher,
            publication_date = EXCLUDED.publication_date,
            language = EXCLUDED.language,
            pages = EXCLUDED.pages,
            description = EXCLUDED.description,
            total_copies = EXCLUDED.total_copies,
            available_copies = EXCLUDED.available_copies,
            price = EXCLUDED.price,
            cover_image_url = EXCLUDED.cover_image_url,
            dewey_decimal = EXCLUDED.dewey_decimal,
            library_of_congress_code = EXCLUDED.library_of_congress_code,
            call_number = EXCLUDED.call_number,
            audience_level = EXCLUDED.audience_level,
            subject_headings = EXCLUDED.subject_headings,
            active = EXCLUDED.active,
            updated_at = CURRENT_TIMESTAMP
        """;

    private static final String INSERT_ONLY_SQL = """
        INSERT INTO books (
            isbn, title, author, genre_id, publisher, publication_date, language, pages, description,
            total_copies, available_copies, price, cover_image_url, dewey_decimal, library_of_congress_code,
            call_number, audience_level, subject_headings, active, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """;

    private final JdbcTemplate jdbcTemplate;
    private final BookRepository bookRepository;
    private final GenreRepository genreRepository;
    @Qualifier("eventExecutor")
    private final Executor eventExecutor;
    private final Map<String, BookImportJobDTO> jobs = new ConcurrentHashMap<>();

    @Override
    public BookImportJobDTO startCsvImport(MultipartFile file, BookImportRequest request) throws BookException {
        if (file == null || file.isEmpty()) {
            throw new BookException("CSV file is required");
        }

        BookImportRequest safeRequest = normalizeRequest(request);
        final byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException ex) {
            throw new BookException("Unable to read uploaded CSV file");
        }

        BookImportJobDTO job = createJob(
            BookImportSourceType.CSV,
            file.getOriginalFilename() != null ? file.getOriginalFilename() : "uploaded.csv"
        );
        eventExecutor.execute(() -> processCsv(job.getJobId(), safeRequest, fileBytes));
        return copyJob(job);
    }

    @Override
    public BookImportJobDTO getImportJob(String jobId) throws BookException {
        BookImportJobDTO job = jobs.get(jobId);
        if (job == null) {
            throw new BookException("Import job not found: " + jobId);
        }
        return copyJob(job);
    }

    private void processCsv(String jobId, BookImportRequest request, byte[] fileBytes) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(fileBytes), StandardCharsets.UTF_8)
        )) {
            BookImportJobDTO job = jobs.get(jobId);
            updateStage(job, "Parsing CSV file");

            String headerLine = readNextCsvRecord(reader);
            if (!StringUtils.hasText(headerLine)) {
                throw new BookException("CSV file is empty");
            }

            List<String> headers = parseCsvLine(headerLine).stream()
                .map(this::stripUtf8Bom)
                .map(this::normalizeHeader)
                .toList();

            List<BookImportRecordDTO> records = new ArrayList<>();
            String line;
            while ((line = readNextCsvRecord(reader)) != null) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                List<String> values = parseCsvLine(line);
                records.add(mapCsvRecord(headers, values));
            }

            runJob(jobId, "Importing CSV records", request, records);
        } catch (Exception ex) {
            failJob(jobId, ex);
        }
    }

    private void runJob(String jobId, String initialStage, BookImportRequest request, List<BookImportRecordDTO> rawRecords) {
        BookImportJobDTO job = jobs.get(jobId);
        try {
            markRunning(job, initialStage);
            validateDefaultGenre(request.getDefaultGenreId());

            int chunkSize = request.getChunkSize() != null ? request.getChunkSize() : 1000;
            Map<String, Long> genreCache = new ConcurrentHashMap<>();
            preloadGenreCache(genreCache, request.getDefaultGenreId());

            for (int start = 0; start < rawRecords.size(); start += chunkSize) {
                int end = Math.min(start + chunkSize, rawRecords.size());
                updateStage(job, "Importing records " + (start + 1) + "-" + end + " of " + rawRecords.size());
                List<NormalizedBookRecord> batch = normalizeBatch(rawRecords.subList(start, end), request, job, genreCache);
                flushBatch(batch, request, job);
            }

            job.setStatus(BookImportStatus.COMPLETED);
            job.setCurrentStage("Completed");
            job.setFinishedAt(LocalDateTime.now());
        } catch (Exception ex) {
            failJob(jobId, ex);
        }
    }

    private void flushBatch(List<NormalizedBookRecord> batch, BookImportRequest request, BookImportJobDTO job) {
        if (batch.isEmpty()) {
            return;
        }

        Set<String> isbns = batch.stream()
            .map(NormalizedBookRecord::isbn)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> existingIsbns = isbns.isEmpty() ? Collections.emptySet() : bookRepository.findExistingIsbns(isbns);

        List<NormalizedBookRecord> recordsToPersist = batch;
        if (!request.isAllowUpdates() && !existingIsbns.isEmpty()) {
            recordsToPersist = batch.stream()
                .filter(record -> !existingIsbns.contains(record.isbn()))
                .toList();
            job.setSkippedRecords(job.getSkippedRecords() + existingIsbns.size());
        }

        if (recordsToPersist.isEmpty()) {
            return;
        }

        String sql = request.isAllowUpdates() ? UPSERT_SQL : INSERT_ONLY_SQL;
        List<NormalizedBookRecord> batchToPersist = recordsToPersist;
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                bindRecord(ps, batchToPersist.get(i));
            }

            @Override
            public int getBatchSize() {
                return batchToPersist.size();
            }
        });

        long created = batchToPersist.stream().filter(record -> !existingIsbns.contains(record.isbn())).count();
        long updated = request.isAllowUpdates()
            ? batchToPersist.stream().filter(record -> existingIsbns.contains(record.isbn())).count()
            : 0;
        job.setCreatedRecords(job.getCreatedRecords() + created);
        job.setUpdatedRecords(job.getUpdatedRecords() + updated);
    }

    private List<NormalizedBookRecord> normalizeBatch(
        List<BookImportRecordDTO> batch,
        BookImportRequest request,
        BookImportJobDTO job,
        Map<String, Long> genreCache
    ) {
        List<NormalizedBookRecord> normalized = new ArrayList<>();
        for (BookImportRecordDTO record : batch) {
            try {
                normalized.add(normalizeRecord(record, request, genreCache));
            } catch (Exception ex) {
                job.setFailedRecords(job.getFailedRecords() + 1);
                appendError(job, ex.getMessage());
            } finally {
                job.setProcessedRecords(job.getProcessedRecords() + 1);
            }
        }
        return normalized;
    }

    private NormalizedBookRecord normalizeRecord(
        BookImportRecordDTO record,
        BookImportRequest request,
        Map<String, Long> genreCache
    ) throws BookException {
        if (record == null) {
            throw new BookException("Encountered empty record");
        }

        String isbn = trim(record.getIsbn());
        String title = trim(record.getTitle());
        String author = trim(record.getAuthor());
        if (!StringUtils.hasText(isbn) || !StringUtils.hasText(title) || !StringUtils.hasText(author)) {
            throw new BookException("isbn, title and author are required for every imported book");
        }

        Integer totalCopies = firstNonNullPositive(record.getTotalCopies(), request.getDefaultTotalCopies(), 1);
        Integer availableCopies = firstNonNullPositive(record.getAvailableCopies(), request.getDefaultAvailableCopies(), totalCopies);
        availableCopies = Math.min(availableCopies, totalCopies);
        BigDecimal price = record.getPrice() != null ? record.getPrice() : defaultPrice(request);
        if (price.compareTo(BigDecimal.ZERO) < 0) {
            price = BigDecimal.ZERO;
        }

        return new NormalizedBookRecord(
            isbn,
            title,
            author,
            resolveGenreId(record, request, genreCache),
            trim(record.getPublisher()),
            record.getPublicationDate(),
            StringUtils.hasText(record.getLanguage()) ? trim(record.getLanguage()) : defaultLanguage(request),
            record.getPages(),
            trim(record.getDescription()),
            totalCopies,
            availableCopies,
            price,
            trim(record.getCoverImageUrl()),
            trim(record.getDeweyDecimal()),
            trim(record.getLibraryOfCongressCode()),
            trim(record.getCallNumber()),
            trim(record.getAudienceLevel()),
            trim(record.getSubjectHeadings()),
            record.getActive() == null || record.getActive()
        );
    }

    private long resolveGenreId(BookImportRecordDTO record, BookImportRequest request, Map<String, Long> genreCache) throws BookException {
        if (record.getGenreId() != null) {
            validateDefaultGenre(record.getGenreId());
            return record.getGenreId();
        }

        String genreCode = normalizeGenreCode(record.getGenreCode());
        if (StringUtils.hasText(genreCode)) {
            String key = "code:" + genreCode;
            if (genreCache.containsKey(key)) {
                return genreCache.get(key);
            }
            Genre genre = genreRepository.findByCode(genreCode)
                .orElseGet(() -> createGenreIfAllowed(request, genreCode, record.getGenreName()));
            genreCache.put(key, genre.getId());
            genreCache.put("name:" + genre.getName().toLowerCase(Locale.ROOT), genre.getId());
            return genre.getId();
        }

        String genreName = trim(record.getGenreName());
        if (StringUtils.hasText(genreName)) {
            String key = "name:" + genreName.toLowerCase(Locale.ROOT);
            if (genreCache.containsKey(key)) {
                return genreCache.get(key);
            }
            Genre genre = genreRepository.findFirstByNameIgnoreCase(genreName)
                .orElseGet(() -> createGenreIfAllowed(request, null, genreName));
            genreCache.put(key, genre.getId());
            genreCache.put("code:" + genre.getCode(), genre.getId());
            return genre.getId();
        }

        if (request.getDefaultGenreId() != null) {
            return request.getDefaultGenreId();
        }

        throw new BookException("Genre information missing and defaultGenreId not provided");
    }

    private Genre createGenreIfAllowed(BookImportRequest request, String genreCode, String genreName) {
        if (!request.isAutoCreateGenres()) {
            throw new InvalidRequestException("Genre not found and autoCreateGenres is disabled");
        }

        String effectiveName = StringUtils.hasText(genreName) ? trim(genreName) : prettifyGenreCode(genreCode);
        String baseCode = StringUtils.hasText(genreCode) ? genreCode : normalizeGenreCode(effectiveName);
        String uniqueCode = ensureUniqueGenreCode(baseCode);

        Genre genre = new Genre();
        genre.setCode(uniqueCode);
        genre.setName(effectiveName);
        genre.setDescription("Auto-created during large catalog import");
        genre.setActive(true);
        genre.setDisplayOrder(0);
        genre.setClassificationSystem("CUSTOM");
        genre.setClassificationCode(uniqueCode);
        genre.setHierarchyPath(effectiveName);
        genre.setHierarchyLevel(0);
        return genreRepository.save(genre);
    }

    private String ensureUniqueGenreCode(String baseCode) {
        String normalizedBase = StringUtils.hasText(baseCode) ? baseCode : "GENERAL";
        String candidate = normalizedBase;
        int suffix = 1;
        while (genreRepository.existsByCode(candidate)) {
            candidate = normalizedBase + "_" + suffix++;
        }
        return candidate;
    }

    private void preloadGenreCache(Map<String, Long> genreCache, Long defaultGenreId) {
        if (defaultGenreId != null) {
            genreCache.put("id:" + defaultGenreId, defaultGenreId);
        }
    }

    private void validateDefaultGenre(Long genreId) throws BookException {
        if (genreId != null && !genreRepository.existsById(genreId)) {
            throw new BookException("Genre not found: " + genreId);
        }
    }

    private BookImportRequest normalizeRequest(BookImportRequest request) {
        BookImportRequest safe = request != null ? request : new BookImportRequest();
        if (safe.getChunkSize() == null) {
            safe.setChunkSize(1000);
        }
        safe.setChunkSize(Math.max(100, Math.min(5000, safe.getChunkSize())));
        if (safe.getBooks() == null) {
            safe.setBooks(new ArrayList<>());
        }
        return safe;
    }

    private BookImportJobDTO createJob(BookImportSourceType sourceType, String sourceLabel) {
        BookImportJobDTO job = BookImportJobDTO.builder()
            .jobId(UUID.randomUUID().toString())
            .status(BookImportStatus.QUEUED)
            .sourceType(sourceType)
            .sourceLabel(sourceLabel)
            .currentStage("Queued")
            .startedAt(LocalDateTime.now())
            .errors(new ArrayList<>())
            .build();
        jobs.put(job.getJobId(), job);
        return job;
    }

    private void markRunning(BookImportJobDTO job, String stage) {
        job.setStatus(BookImportStatus.RUNNING);
        updateStage(job, stage);
    }

    private void updateStage(BookImportJobDTO job, String stage) {
        job.setCurrentStage(stage);
    }

    private void failJob(String jobId, Exception ex) {
        BookImportJobDTO job = jobs.get(jobId);
        if (job == null) {
            return;
        }
        job.setStatus(BookImportStatus.FAILED);
        job.setCurrentStage("Failed");
        job.setFinishedAt(LocalDateTime.now());
        appendError(job, ex.getMessage() != null ? ex.getMessage() : "Import failed");
        log.error("Book import job {} failed", jobId, ex);
    }

    private void appendError(BookImportJobDTO job, String message) {
        if (job.getErrors().size() < 25) {
            job.getErrors().add(message);
        }
    }

    private BookImportJobDTO copyJob(BookImportJobDTO job) {
        return BookImportJobDTO.builder()
            .jobId(job.getJobId())
            .status(job.getStatus())
            .sourceType(job.getSourceType())
            .sourceLabel(job.getSourceLabel())
            .currentStage(job.getCurrentStage())
            .processedRecords(job.getProcessedRecords())
            .createdRecords(job.getCreatedRecords())
            .updatedRecords(job.getUpdatedRecords())
            .skippedRecords(job.getSkippedRecords())
            .failedRecords(job.getFailedRecords())
            .errors(new ArrayList<>(job.getErrors()))
            .startedAt(job.getStartedAt())
            .finishedAt(job.getFinishedAt())
            .build();
    }

    private BookImportRecordDTO mapCsvRecord(List<String> headers, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            row.put(headers.get(i), i < values.size() ? trim(values.get(i)) : null);
        }

        BookImportRecordDTO record = new BookImportRecordDTO();
        record.setIsbn(row.get("isbn"));
        record.setTitle(firstPresent(row, "title", "booktitle", "name"));
        record.setAuthor(firstPresent(row, "author", "authors", "bookauthor"));
        record.setGenreId(parseLong(firstPresent(row, "genreid", "genre_id")));
        record.setGenreName(firstPresent(row, "genrename", "genre", "category"));
        record.setGenreCode(firstPresent(row, "genrecode", "genre_code"));
        record.setPublisher(firstPresent(row, "publisher"));
        record.setPublicationDate(parseDate(firstPresent(row, "publicationdate", "publication_date", "publisheddate")));
        record.setLanguage(firstPresent(row, "language"));
        record.setPages(parseInteger(firstPresent(row, "pages", "pagecount")));
        record.setDescription(firstPresent(row, "description", "summary"));
        record.setTotalCopies(parseInteger(firstPresent(row, "totalcopies", "total_copies", "copies")));
        record.setAvailableCopies(parseInteger(firstPresent(row, "availablecopies", "available_copies")));
        record.setPrice(parseDecimal(firstPresent(row, "price", "cost")));
        record.setCoverImageUrl(firstPresent(row, "coverimageurl", "cover_image_url", "image", "imageurl", "thumbnail"));
        record.setDeweyDecimal(firstPresent(row, "deweydecimal", "dewey_decimal", "ddc"));
        record.setLibraryOfCongressCode(firstPresent(row, "libraryofcongresscode", "library_of_congress_code", "lcc", "lcccode"));
        record.setCallNumber(firstPresent(row, "callnumber", "call_number"));
        record.setAudienceLevel(firstPresent(row, "audiencelevel", "audience_level", "audience"));
        record.setSubjectHeadings(firstPresent(row, "subjectheadings", "subject_headings", "subjects"));
        record.setActive(parseBoolean(firstPresent(row, "active", "isactive", "enabled")));
        return record;
    }

    private String readNextCsvRecord(BufferedReader reader) throws IOException, BookException {
        StringBuilder record = new StringBuilder();
        String line;
        boolean hasAnyLine = false;
        while ((line = reader.readLine()) != null) {
            if (!hasAnyLine && !StringUtils.hasText(line)) {
                continue;
            }
            if (hasAnyLine) {
                record.append('\n');
            }
            record.append(line);
            hasAnyLine = true;
            if (!hasUnclosedQuotes(record)) {
                return record.toString();
            }
        }

        if (!hasAnyLine) {
            return null;
        }
        throw new BookException("Malformed CSV: unterminated quoted field");
    }

    private boolean hasUnclosedQuotes(CharSequence value) {
        boolean inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    i++;
                    continue;
                }
                inQuotes = !inQuotes;
            }
        }
        return inQuotes;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private String normalizeHeader(String header) {
        return trim(header).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String stripUtf8Bom(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private String firstPresent(Map<String, String> row, String... keys) {
        return Arrays.stream(keys)
            .map(row::get)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private Integer parseInteger(String value) {
        try {
            return StringUtils.hasText(value) ? Integer.parseInt(value.trim()) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long parseLong(String value) {
        try {
            return StringUtils.hasText(value) ? Long.parseLong(value.trim()) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String value) {
        try {
            return StringUtils.hasText(value) ? new BigDecimal(value.trim()) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return StringUtils.hasText(value) ? LocalDate.parse(value.trim()) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> null;
        };
    }

    private Integer firstNonNullPositive(Integer primary, Integer secondary, Integer fallback) {
        int value = primary != null ? primary : (secondary != null ? secondary : fallback);
        return Math.max(0, value);
    }

    private BigDecimal defaultPrice(BookImportRequest request) {
        return request.getDefaultPrice() != null ? request.getDefaultPrice() : BigDecimal.ZERO;
    }

    private String defaultLanguage(BookImportRequest request) {
        return StringUtils.hasText(request.getDefaultLanguage()) ? trim(request.getDefaultLanguage()) : "English";
    }

    private String normalizeGenreCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim()
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    }

    private String prettifyGenreCode(String genreCode) {
        if (!StringUtils.hasText(genreCode)) {
            return "General";
        }
        return Arrays.stream(genreCode.split("_"))
            .filter(StringUtils::hasText)
            .map(part -> part.substring(0, 1) + part.substring(1).toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(" "));
    }

    private void bindRecord(PreparedStatement ps, NormalizedBookRecord record) throws SQLException {
        ps.setString(1, record.isbn());
        ps.setString(2, record.title());
        ps.setString(3, record.author());
        ps.setLong(4, record.genreId());
        setNullableString(ps, 5, record.publisher());
        if (record.publicationDate() != null) {
            ps.setDate(6, Date.valueOf(record.publicationDate()));
        } else {
            ps.setNull(6, Types.DATE);
        }
        setNullableString(ps, 7, record.language());
        if (record.pages() != null) {
            ps.setInt(8, record.pages());
        } else {
            ps.setNull(8, Types.INTEGER);
        }
        setNullableString(ps, 9, record.description());
        ps.setInt(10, record.totalCopies());
        ps.setInt(11, record.availableCopies());
        ps.setBigDecimal(12, record.price());
        setNullableString(ps, 13, record.coverImageUrl());
        setNullableString(ps, 14, record.deweyDecimal());
        setNullableString(ps, 15, record.libraryOfCongressCode());
        setNullableString(ps, 16, record.callNumber());
        setNullableString(ps, 17, record.audienceLevel());
        setNullableString(ps, 18, record.subjectHeadings());
        ps.setBoolean(19, record.active());
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (StringUtils.hasText(value)) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private record NormalizedBookRecord(
        String isbn,
        String title,
        String author,
        long genreId,
        String publisher,
        LocalDate publicationDate,
        String language,
        Integer pages,
        String description,
        Integer totalCopies,
        Integer availableCopies,
        BigDecimal price,
        String coverImageUrl,
        String deweyDecimal,
        String libraryOfCongressCode,
        String callNumber,
        String audienceLevel,
        String subjectHeadings,
        boolean active
    ) {
    }
}

