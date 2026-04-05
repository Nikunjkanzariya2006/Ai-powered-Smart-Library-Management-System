package com.nik.payload.request;

import com.nik.payload.dto.BookImportRecordDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookImportRequest {

    private String remoteUrl;
    private Long defaultGenreId;
    private boolean allowUpdates = true;
    private boolean autoCreateGenres = false;

    @Min(100)
    @Max(5000)
    private Integer chunkSize = 1000;

    @Min(0)
    private Integer defaultTotalCopies = 1;

    @Min(0)
    private Integer defaultAvailableCopies = 1;

    private BigDecimal defaultPrice = BigDecimal.ZERO;
    private String defaultLanguage = "English";
    private List<BookImportRecordDTO> books = new ArrayList<>();
}
