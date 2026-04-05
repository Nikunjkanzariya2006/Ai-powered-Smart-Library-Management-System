package com.nik.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompleteProfileRequest {

    private String fullName;

    private String phone;

    private List<Long> interestGenreIds;
}
