package com.crates.crates.DTO;

public record ApiResponse<T>(boolean success, T data, String message) {
}
