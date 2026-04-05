package com.nik.payload.request;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;



@Data
public class ResetPasswordRequest {
    @NotBlank(message = "Token is mandatory")
    private String token;

    @NotBlank(message = "Password is mandatory")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;
}
