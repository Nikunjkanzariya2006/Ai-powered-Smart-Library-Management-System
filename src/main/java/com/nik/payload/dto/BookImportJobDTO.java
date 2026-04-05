package com.nik.payload.dto;

import com.nik.domain.BookImportSourceType;
import com.nik.domain.BookImportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookImportJobDTO {

    private String jobId;
    private BookImportStatus status;
    private BookImportSourceType sourceType;
    private String sourceLabel;
    private String currentStage;
    private long processedRecords;
    private long createdRecords;
    private long updatedRecords;
    private long skippedRecords;
    private long failedRecords;
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
