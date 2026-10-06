package com.versioncontrol.entity;

import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "labels", uniqueConstraints = @UniqueConstraint(name = "uk_labels_repository_name", columnNames = {"repository_id", "name"}))
public class Label {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;
    @Column(nullable = false, length = 50)
    private String name;
    @Column(length = 255)
    private String description;
    @Column(nullable = false, length = 7)
    private String color;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    protected Label() { }
    public Label(Repository repository, String name, String description, String color) {
        this.repository = repository; this.name = name; this.description = description; this.color = color;
    }
    public Long getId() { return id; }
    public Repository getRepository() { return repository; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
