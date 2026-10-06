package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.UserFollow;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

    boolean existsByFollower_IdAndFollowing_Id(Long followerId, Long followingId);
}

