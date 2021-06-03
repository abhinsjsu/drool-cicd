package com.amazon.awsgurufrontendservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CommentWithCategory {
    private String commentId;
    private String body;
    private String category;
}