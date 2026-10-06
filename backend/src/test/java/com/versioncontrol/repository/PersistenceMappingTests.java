package com.versioncontrol.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.Issue;
import com.versioncontrol.entity.IssueStatus;
import com.versioncontrol.entity.Milestone;
import com.versioncontrol.entity.RepositoryStar;
import com.versioncontrol.entity.RepositoryVisibility;
import com.versioncontrol.entity.User;
import com.versioncontrol.entity.UserFollow;

@DataJpaTest
class PersistenceMappingTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private RepositoryStarRepository repositoryStarRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Test
    void savesCoreEntitiesAndRelationships() {
        User owner = userRepository.saveAndFlush(
                new User("owner", "owner@example.test", "encoded-value")
        );
        User follower = userRepository.saveAndFlush(
                new User("follower", "follower@example.test", "encoded-value")
        );

        Repository repository = repositoryRepository.saveAndFlush(
                new Repository("project", "description", RepositoryVisibility.PRIVATE, owner)
        );
        UserFollow follow = userFollowRepository.saveAndFlush(new UserFollow(follower, owner));
        RepositoryStar star = repositoryStarRepository.saveAndFlush(
                new RepositoryStar(follower, repository)
        );

        assertThat(repository.getId()).isNotNull();
        assertThat(follow.getId()).isNotNull();
        assertThat(star.getId()).isNotNull();
        assertThat(repositoryRepository.existsByOwner_IdAndName(owner.getId(), "project")).isTrue();
        assertThat(userFollowRepository.existsByFollower_IdAndFollowing_Id(
                follower.getId(), owner.getId())).isTrue();
        assertThat(repositoryStarRepository.existsByUser_IdAndRepository_Id(
                follower.getId(), repository.getId())).isTrue();
    }

    @Test
    void repositoryNameIsUniquePerOwnerButMayRepeatAcrossOwners() {
        User firstOwner = userRepository.saveAndFlush(
                new User("first-owner", "first@example.test", "encoded-value")
        );
        User secondOwner = userRepository.saveAndFlush(
                new User("second-owner", "second@example.test", "encoded-value")
        );

        repositoryRepository.saveAndFlush(
                new Repository("same-name", null, RepositoryVisibility.PUBLIC, firstOwner)
        );
        repositoryRepository.saveAndFlush(
                new Repository("same-name", null, RepositoryVisibility.PUBLIC, secondOwner)
        );

        assertThrows(DataIntegrityViolationException.class, () ->
                repositoryRepository.saveAndFlush(
                        new Repository("same-name", null, RepositoryVisibility.PRIVATE, firstOwner)
                )
        );
    }

    @Test
    void userCannotFollowThemselves() {
        User user = userRepository.saveAndFlush(
                new User("self", "self@example.test", "encoded-value")
        );

        assertThrows(IllegalArgumentException.class, () ->
                userFollowRepository.saveAndFlush(new UserFollow(user, user))
        );
    }

    @Test
    void milestoneAndOptionalIssueRelationshipArePersisted() {
        User owner = userRepository.saveAndFlush(new User("milestone-owner", "milestone-owner@example.test", "encoded-value"));
        Repository repository = repositoryRepository.saveAndFlush(new Repository("milestone-project", null, RepositoryVisibility.PUBLIC, owner));
        Milestone milestone = milestoneRepository.saveAndFlush(new Milestone(repository, "release", null, null));
        Issue issue = issueRepository.saveAndFlush(new Issue(repository, owner, null, "ticket", null));

        assertThat(milestone.getId()).isNotNull();
        assertThat(issue.getMilestone()).isNull();
        issue.setMilestone(milestone);
        issueRepository.saveAndFlush(issue);
        assertThat(issueRepository.findById(issue.getId()).orElseThrow().getMilestone().getId()).isEqualTo(milestone.getId());
        issue.setMilestone(null);
        issueRepository.saveAndFlush(issue);
        assertThat(issueRepository.findById(issue.getId()).orElseThrow().getMilestone()).isNull();
    }
}

