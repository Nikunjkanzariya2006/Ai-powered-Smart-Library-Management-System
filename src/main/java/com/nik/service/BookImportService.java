package com.nik.service;

import com.nik.exception.BookException;
import com.nik.payload.dto.BookImportJobDTO;
import com.nik.payload.request.BookImportRequest;
import org.springframework.web.multipart.MultipartFile;

public interface BookImportService {

    BookImportJobDTO startCsvImport(MultipartFile file, BookImportRequest request) throws BookException;

    BookImportJobDTO getImportJob(String jobId) throws BookException;
}
