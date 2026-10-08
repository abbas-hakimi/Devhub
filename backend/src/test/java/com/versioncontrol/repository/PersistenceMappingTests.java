package com.versioncontrol.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.Issue;
import com.versioncontrol.entity.IssueStatus;
import com.versioncontrol.entity.Blob;
import com.versioncontrol.entity.Branch;
import com.versioncontrol.entity.Commit;
import com.versioncontrol.entity.Milestone;
import com.versioncontrol.entity.RepositoryStar;
import com.versioncontrol.entity.RepositoryVisibility;
import com.versioncontrol.entity.Tree;
import com.versioncontrol.entity.TreeEntry;
import com.versioncontrol.entity.TreeEntryType;
import com.versioncontrol.entity.User;
import com.versioncontrol.entity.UserFollow;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void mapsVersionControlEntitiesAndLazyRelationships() {
        for (Class<?> type : new Class<?>[] {Branch.class, Blob.class, Tree.class, TreeEntry.class, Commit.class}) {
            assertThat(entityManager.getMetamodel().entity(type)).isNotNull();
        }

        User author = new User("git-author", "git-author@example.test", "encoded-value");
        userRepository.saveAndFlush(author);
        Repository repository = repositoryRepository.saveAndFlush(
                new Repository("git-project", null, RepositoryVisibility.PUBLIC, author));
        Tree root = persist(new Tree("1".repeat(64)));
        Tree child = persist(new Tree("2".repeat(64)));
        Blob blob = persist(new Blob("a".repeat(64), 12));
        TreeEntry file = persist(new TreeEntry(root, "README.md", TreeEntryType.FILE, blob, null));
        TreeEntry directory = persist(new TreeEntry(root, "src", TreeEntryType.DIRECTORY, null, child));
        Commit initial = persist(new Commit(repository, "3".repeat(64), "Initial", author, root, null));
        Commit next = persist(new Commit(repository, "4".repeat(64), "Add files", author, root, initial));
        Branch branch = persist(new Branch(repository, "main", next));

        assertThat(initial.getParentCommit()).isNull();
        assertThat(next.getParentCommit().getId()).isEqualTo(initial.getId());
        assertThat(branch.getHeadCommit().getId()).isEqualTo(next.getId());
        assertThat(file.getBlob().getObjectHash()).isEqualTo("a".repeat(64));
        assertThat(directory.getChildTree().getTreeHash()).isEqualTo("2".repeat(64));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT entry_type FROM tree_entries WHERE id = ?", String.class, file.getId())).isEqualTo("FILE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT entry_type FROM tree_entries WHERE id = ?", String.class, directory.getId())).isEqualTo("DIRECTORY");

        entityManager.clear();
        Branch loaded = entityManager.find(Branch.class, branch.getId());
        assertThat(org.hibernate.Hibernate.isInitialized(loaded.getRepository())).isFalse();
        assertThat(org.hibernate.Hibernate.isInitialized(loaded.getHeadCommit())).isFalse();
    }

    @Test
    void branchNamesAreValidatedAndUniquePerRepository() {
        User owner = user("branch-owner");
        Repository repository = repositoryRepository.saveAndFlush(
                new Repository("branch-project", null, RepositoryVisibility.PUBLIC, owner));
        Tree tree = persist(new Tree("5".repeat(64)));
        Commit head = persist(new Commit(repository, "6".repeat(64), "Initial", owner, tree, null));
        Branch main = persist(new Branch(repository, "feature/auth", head));
        assertThat(main.getName()).isEqualTo("feature/auth");
        assertThat(persist(new Branch(repository, "FEATURE/auth", head)).getName()).isEqualTo("FEATURE/auth");
        assertThrows(IllegalArgumentException.class, () -> new Branch(repository, "../main", head));
        assertThrows(IllegalArgumentException.class, () -> new Branch(repository, "main name", head));
        assertThrows(IllegalArgumentException.class, () -> new Branch(repository, "topic.lock", head));
        assertThrows(IllegalArgumentException.class, () -> new Branch(repository, "HEAD", head));
        assertThrows(IllegalArgumentException.class, () -> new Branch(repository, "é".repeat(128), head));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO branches (repository_id, name, head_commit_id) VALUES (?, ?, ?)",
                repository.getId(), "feature/auth", head.getId()));
    }

    @Test
    void blobAndTreeHashesAreUniqueAndValidateLowercaseSha256Shape() {
        persist(new Blob("7".repeat(64), 0));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO blobs (object_hash, size) VALUES (?, ?)", "7".repeat(64), 1));
    }

    @Test
    void treeHashIsUnique() {
        persist(new Tree("8".repeat(64)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO trees (tree_hash) VALUES (?)", "8".repeat(64)));
    }

    @Test
    void commitHashIsUnique() {
        User author = user("commit-hash-owner");
        Repository repository = repositoryRepository.saveAndFlush(
                new Repository("commit-hash-project", null, RepositoryVisibility.PUBLIC, author));
        Tree tree = persist(new Tree("8".repeat(64)));
        persist(new Commit(repository, "9".repeat(64), "Initial", author, tree, null));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO commits (repository_id, commit_hash, message, author_id, root_tree_id) VALUES (?, ?, ?, ?, ?)",
                repository.getId(), "9".repeat(64), "Duplicate hash", author.getId(), tree.getId()));
    }

    @Test
    void treeEntryNameIsNormalizedAndUniqueWithinTree() {
        Tree tree = persist(new Tree("9".repeat(64)));
        Blob blob = persist(new Blob("b".repeat(64), 1));
        TreeEntry first = persist(new TreeEntry(tree, "e\u0301", TreeEntryType.FILE, blob, null));
        assertThat(first.getName()).isEqualTo("é");
        assertThrows(IllegalArgumentException.class,
                () -> new TreeEntry(tree, "../escape", TreeEntryType.FILE, blob, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO tree_entries (tree_id, name, entry_type, blob_id) VALUES (?, ?, 'FILE', ?)",
                tree.getId(), "é", blob.getId()));
    }

    @Test
    void databaseRejectsInvalidTreeEntryTargetsAndCrossRepositoryReferences() {
        User owner = user("boundary-owner");
        Repository firstRepository = repositoryRepository.saveAndFlush(
                new Repository("boundary-first", null, RepositoryVisibility.PUBLIC, owner));
        Repository secondRepository = repositoryRepository.saveAndFlush(
                new Repository("boundary-second", null, RepositoryVisibility.PUBLIC, owner));
        Tree root = persist(new Tree("c".repeat(64)));
        Tree child = persist(new Tree("d".repeat(64)));
        Commit firstCommit = persist(new Commit(firstRepository, "e".repeat(64), "Initial", owner, root, null));

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO tree_entries (tree_id, name, entry_type, blob_id, child_tree_id) VALUES (?, ?, 'FILE', NULL, ?)",
                root.getId(), "invalid", child.getId()));
        Blob blob = persist(new Blob("c".repeat(64), 1));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO tree_entries (tree_id, name, entry_type, blob_id) VALUES (?, ?, 'FILE', ?)",
                root.getId(), "invalid/name", blob.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO branches (repository_id, name, head_commit_id) VALUES (?, ?, ?)",
                secondRepository.getId(), "wrong-head", firstCommit.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO commits (repository_id, commit_hash, message, author_id, root_tree_id, parent_commit_id) VALUES (?, ?, ?, ?, ?, ?)",
                secondRepository.getId(), "f".repeat(64), "Wrong parent", owner.getId(), root.getId(), firstCommit.getId()));
    }

    @Test
    void repositoryDeletionIsRestrictedByVersionControlRows() {
        User owner = user("git-delete-owner");
        Repository repository = repositoryRepository.saveAndFlush(
                new Repository("git-delete-project", null, RepositoryVisibility.PUBLIC, owner));
        Tree tree = persist(new Tree("a".repeat(64)));
        Commit initial = persist(new Commit(repository, "b".repeat(64), "Initial", owner, tree, null));
        persist(new Branch(repository, "main", initial));

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM repositories WHERE id = ?", repository.getId()));
    }

    private User user(String prefix) {
        String suffix = Long.toString(System.nanoTime());
        return userRepository.saveAndFlush(new User(prefix + suffix, prefix + suffix + "@example.test", "encoded-value"));
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}

