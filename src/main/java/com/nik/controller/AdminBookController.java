package com.nik.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nik.exception.BookException;
import com.nik.payload.request.BookImportRequest;
import com.nik.payload.response.ApiResponse;
import com.nik.payload.dto.BookDTO;
import com.nik.payload.dto.BookImportJobDTO;
import com.nik.service.BookImportService;
import com.nik.service.BookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/books")
public class AdminBookController {

    private final BookService bookService;
    private final BookImportService bookImportService;
    private final ObjectMapper objectMapper;

    /**
     * Create a new book
     * POST /api/books
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createBook(
            @Valid @RequestBody BookDTO bookDTO) {
        try {
            BookDTO createdBook = bookService.createBook(bookDTO);
            return new ResponseEntity<>(createdBook, HttpStatus.CREATED);
        } catch (BookException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(e.getMessage(), false));
        }
    }

    @PostMapping(value = "/import/csv", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookImportJobDTO> importBooksFromCsv(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "options", required = false) String optionsJson) throws BookException {
        return ResponseEntity.accepted().body(bookImportService.startCsvImport(file, parseImportOptions(optionsJson)));
    }

    private BookImportRequest parseImportOptions(String optionsJson) throws BookException {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(optionsJson, BookImportRequest.class);
        } catch (Exception ex) {
            throw new BookException("Invalid CSV import options payload");
        }
    }

    @GetMapping("/import/jobs/{jobId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookImportJobDTO> getImportJob(@PathVariable String jobId) throws BookException {
        return ResponseEntity.ok(bookImportService.getImportJob(jobId));
    }
}
