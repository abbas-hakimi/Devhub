package com.versioncontrol.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.repository.CommitRepository;

/** Reads and validates the complete two-parent commit DAG for one repository. */
@Service
public class CommitGraphService {
    public static final int MAX_COMMITS = 50_000;
    private final CommitRepository commits;

    public CommitGraphService(CommitRepository commits) { this.commits = commits; }

    public Graph load(Long repositoryId) {
        long count = commits.countByRepository_Id(repositoryId);
        if (count > MAX_COMMITS) throw new InvalidRequestException("Repository history exceeds the merge limit");
        Map<Long, CommitRepository.LineageRow> rows = new HashMap<>();
        for (CommitRepository.LineageRow row : commits.findAllLineageByRepository_Id(repositoryId)) rows.put(row.getId(), row);
        for (CommitRepository.LineageRow row : rows.values()) {
            verifyParent(repositoryId, row.getParentId(), row.getParentRepositoryId(), rows);
            verifyParent(repositoryId, row.getSecondParentId(), row.getSecondParentRepositoryId(), rows);
        }
        return new Graph(repositoryId, rows);
    }

    private void verifyParent(Long repositoryId, Long parentId, Long parentRepositoryId,
            Map<Long, CommitRepository.LineageRow> rows) {
        if (parentId == null) return;
        if (!rows.containsKey(parentId) || !repositoryId.equals(parentRepositoryId)) {
            throw new RepositoryConflictException("Commit history contains a missing or cross-repository parent");
        }
    }

    public static final class Graph {
        private final Long repositoryId;
        private final Map<Long, CommitRepository.LineageRow> rows;
        private Graph(Long repositoryId, Map<Long, CommitRepository.LineageRow> rows) {
            this.repositoryId = repositoryId; this.rows = Map.copyOf(rows);
        }

        public Map<Long, Integer> ancestors(Long startId) {
            if (!rows.containsKey(startId)) throw new RepositoryConflictException("Commit history contains a missing commit");
            Map<Long, Integer> distances = new HashMap<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            distances.put(startId, 0); queue.add(startId);
            while (!queue.isEmpty()) {
                Long id = queue.removeFirst();
                int nextDistance = distances.get(id) + 1;
                for (Long parent : parents(rows.get(id))) {
                    Integer prior = distances.get(parent);
                    if (prior == null) { distances.put(parent, nextDistance); queue.addLast(parent); }
                }
                if (distances.size() > MAX_COMMITS) throw new InvalidRequestException("Commit history exceeds the merge limit");
            }
            validateAcyclic(startId);
            return distances;
        }

        public MergeBase mergeBase(Long first, Long second) {
            Map<Long, Integer> left = ancestors(first);
            Map<Long, Integer> right = ancestors(second);
            List<Long> common = left.keySet().stream().filter(right::containsKey).toList();
            if (common.isEmpty()) return null;
            Comparator<Long> proximity = Comparator
                    .comparingInt((Long id) -> Math.max(left.get(id), right.get(id)))
                    .thenComparingInt(id -> left.get(id) + right.get(id))
                    .thenComparing(Comparator.reverseOrder());
            Set<Long> commonSet = new HashSet<>(common);
            Set<Long> dominated = new HashSet<>();
            for (Long candidate : common.stream().sorted(proximity).toList()) {
                if (dominated.contains(candidate)) continue;
                ArrayDeque<Long> walk = new ArrayDeque<>(parents(rows.get(candidate)));
                Set<Long> visited = new HashSet<>();
                while (!walk.isEmpty()) {
                    Long current = walk.removeFirst();
                    if (!visited.add(current)) continue;
                    if (commonSet.contains(current)) dominated.add(current);
                    walk.addAll(parents(rows.get(current)));
                }
            }
            List<Long> bestBases = common.stream().filter(id -> !dominated.contains(id)).toList();
            boolean ambiguous = bestBases.size() > 1;
            Long selected = bestBases.stream().min(proximity).orElseThrow();
            return new MergeBase(selected, rows.get(selected).getCommitHash(),
                    left.size() - common.size(), right.size() - common.size(), ambiguous);
        }

        public boolean isAncestor(Long ancestor, Long descendant) { return ancestors(descendant).containsKey(ancestor); }
        public CommitRepository.LineageRow row(Long id) { return rows.get(id); }
        public Long repositoryId() { return repositoryId; }

        private void validateAcyclic(Long start) {
            Map<Long, Byte> color = new HashMap<>();
            ArrayList<Frame> stack = new ArrayList<>();
            color.put(start, (byte) 1); stack.add(new Frame(start));
            while (!stack.isEmpty()) {
                Frame frame = stack.getLast();
                List<Long> parents = parents(rows.get(frame.id));
                if (frame.index >= parents.size()) { color.put(frame.id, (byte) 2); stack.removeLast(); continue; }
                Long parent = parents.get(frame.index++);
                byte state = color.getOrDefault(parent, (byte) 0);
                if (state == 1) throw new RepositoryConflictException("Commit history contains a cycle");
                if (state == 0) { color.put(parent, (byte) 1); stack.add(new Frame(parent)); }
            }
        }

        private List<Long> parents(CommitRepository.LineageRow row) {
            List<Long> result = new ArrayList<>(2);
            if (row.getParentId() != null) result.add(row.getParentId());
            if (row.getSecondParentId() != null) result.add(row.getSecondParentId());
            return result;
        }
        private static final class Frame { final Long id; int index; Frame(Long id) { this.id = id; } }
    }

    public record MergeBase(Long commitId, String commitHash, int aheadBy, int behindBy, boolean ambiguous) { }
}
