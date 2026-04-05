package com.nik.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "push_tokens",
        indexes = {
                @Index(name = "idx_push_tokens_user_id", columnList = "user_id"),
                @Index(name = "idx_push_tokens_is_active", columnList = "is_active"),
                @Index(name = "idx_push_tokens_last_used_at", columnList = "last_used_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_push_tokens_token",
                        columnNames = "token"
                )
        }
)

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String token;

    @Column(length = 50)
    private String platform; // WEB, ANDROID, IOS

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}

