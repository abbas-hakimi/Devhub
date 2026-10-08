package com.versioncontrol.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.Blob;

public interface BlobRepository extends JpaRepository<Blob, Long> {
    Optional<Blob> findByObjectHash(String objectHash);
}
