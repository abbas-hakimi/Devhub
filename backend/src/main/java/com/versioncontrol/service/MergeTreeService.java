package com.versioncontrol.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.versioncontrol.entity.Blob;
import com.versioncontrol.entity.Tree;
import com.versioncontrol.entity.TreeEntry;
import com.versioncontrol.entity.TreeEntryType;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.repository.BlobRepository;
import com.versioncontrol.repository.TreeEntryRepository;
import com.versioncontrol.repository.TreeRepository;
import com.versioncontrol.versioncontrol.hash.Sha256Hasher;
import com.versioncontrol.versioncontrol.hash.TreeCanonicalizer;
import com.versioncontrol.versioncontrol.hash.TreeHashInput;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;

/** Three-way snapshot merger. Ambiguous and binary same-file edits are conflicts. */
@Service
public class MergeTreeService {
    private static final long MAX_TEXT_MERGE_BYTES = 1_000_000;
    private static final int MAX_PATH_DEPTH = 64;
    private final TreeEntryRepository entries;
    private final TreeRepository trees;
    private final BlobRepository blobs;
    private final ObjectStorage storage;

    public MergeTreeService(TreeEntryRepository entries, TreeRepository trees, BlobRepository blobs, ObjectStorage storage) {
        this.entries = entries; this.trees = trees; this.blobs = blobs; this.storage = storage;
    }

    public Plan plan(Tree base, Tree source, Tree target) {
        Map<String, Blob> baseFiles = flatten(base);
        Map<String, Blob> sourceFiles = flatten(source);
        Map<String, Blob> targetFiles = flatten(target);
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(baseFiles.keySet()); paths.addAll(sourceFiles.keySet()); paths.addAll(targetFiles.keySet());
        Map<String, FileValue> merged = new TreeMap<>();
        TreeSet<String> conflicts = new TreeSet<>();
        for (String path : paths) {
            Blob b = baseFiles.get(path), s = sourceFiles.get(path), t = targetFiles.get(path);
            String bh = hash(b), sh = hash(s), th = hash(t);
            if (same(sh, th)) { if (s != null) merged.put(path, new ExistingFile(s)); continue; }
            if (same(sh, bh)) { if (t != null) merged.put(path, new ExistingFile(t)); continue; }
            if (same(th, bh)) { if (s != null) merged.put(path, new ExistingFile(s)); continue; }
            if (b != null && s != null && t != null) {
                byte[] text = mergeText(b, s, t);
                if (text != null) { merged.put(path, new PendingFile(text)); continue; }
            }
            conflicts.add(path);
        }
        addFileDirectoryConflicts(merged.keySet(), conflicts);
        return new Plan(merged, List.copyOf(conflicts));
    }

    @Transactional
    public Tree persist(Plan plan) {
        if (!plan.conflictPaths().isEmpty()) throw new IllegalArgumentException("cannot persist a conflicted merge tree");
        Map<String, Blob> materialized = new TreeMap<>();
        for (var entry : plan.files().entrySet()) {
            FileValue value = entry.getValue();
            if (value instanceof ExistingFile existing) materialized.put(entry.getKey(), existing.blob());
            else if (value instanceof PendingFile pending) materialized.put(entry.getKey(), persistBlob(pending.bytes()));
        }
        return persistDirectory(buildDirectory(materialized));
    }

    private Map<String, Blob> flatten(Tree root) {
        Map<String, Blob> result = new TreeMap<>();
        flatten(root, "", result, new HashSet<>());
        return result;
    }

    private void flatten(Tree tree, String prefix, Map<String, Blob> result, Set<Long> visiting) {
        if (!visiting.add(tree.getId())) throw new RepositoryConflictException("Stored tree graph contains a cycle");
        for (TreeEntry entry : entries.findByTree_IdOrderByNameAsc(tree.getId())) {
            String path = prefix.isEmpty() ? entry.getName() : prefix + "/" + entry.getName();
            if (entry.getEntryType() == TreeEntryType.FILE) result.put(path, entry.getBlob());
            else flatten(entry.getChildTree(), path, result, visiting);
        }
        visiting.remove(tree.getId());
    }

    private byte[] mergeText(Blob base, Blob source, Blob target) {
        if (base.getSize() > MAX_TEXT_MERGE_BYTES || source.getSize() > MAX_TEXT_MERGE_BYTES
                || target.getSize() > MAX_TEXT_MERGE_BYTES) return null;
        try {
            String b = decode(readVerified(base));
            String s = decode(readVerified(source));
            String t = decode(readVerified(target));
            if (b == null || s == null || t == null) return null;
            String merged = TextMerge.merge(b, s, t);
            return merged == null ? null : merged.getBytes(StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new RepositoryConflictException("A blob required for merge is unavailable or corrupt");
        }
    }

    private byte[] readVerified(Blob blob) throws IOException {
        storage.verify(blob.getObjectHash(), blob.getSize());
        try (var input = storage.read(blob.getObjectHash())) {
            byte[] bytes = input.readNBytes(Math.toIntExact(blob.getSize()) + 1);
            if (bytes.length != blob.getSize()) throw new IOException("stored blob size differs from metadata");
            return bytes;
        }
    }

    private String decode(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) { return null; }
    }

    private Blob persistBlob(byte[] bytes) {
        String hash = Sha256Hasher.hashBytes(bytes);
        Blob blob = blobs.findByObjectHash(hash).orElse(null);
        if (blob != null) {
            if (blob.getSize() != bytes.length) throw new RepositoryConflictException("Stored blob metadata conflicts with its content hash");
            return blob;
        }
        storage.write(hash, new ByteArrayInputStream(bytes), bytes.length);
        return blobs.saveAndFlush(new Blob(hash, bytes.length));
    }

    private Directory buildDirectory(Map<String, Blob> files) {
        Directory root = new Directory();
        for (var file : files.entrySet()) {
            String[] segments = file.getKey().split("/", -1);
            if (segments.length > MAX_PATH_DEPTH) throw new RepositoryConflictException("Merged tree path exceeds maximum depth");
            Directory current = root;
            for (int i = 0; i < segments.length - 1; i++) {
                Object previous = current.children.get(segments[i]);
                if (previous instanceof Blob) throw new RepositoryConflictException("Merge result has a file/directory conflict");
                if (previous == null) { previous = new Directory(); current.children.put(segments[i], previous); }
                current = (Directory) previous;
            }
            String name = segments[segments.length - 1];
            if (current.children.get(name) instanceof Directory) throw new RepositoryConflictException("Merge result has a file/directory conflict");
            current.children.put(name, file.getValue());
        }
        return root;
    }

    private Tree persistDirectory(Directory directory) {
        List<TreeHashInput> inputs = new ArrayList<>();
        Map<String, Tree> childTrees = new HashMap<>();
        for (var child : directory.children.entrySet()) {
            if (child.getValue() instanceof Blob blob) inputs.add(new TreeHashInput(child.getKey(), TreeEntryType.FILE, blob.getObjectHash()));
            else {
                Tree childTree = persistDirectory((Directory) child.getValue());
                childTrees.put(child.getKey(), childTree);
                inputs.add(new TreeHashInput(child.getKey(), TreeEntryType.DIRECTORY, childTree.getTreeHash()));
            }
        }
        byte[] canonical = TreeCanonicalizer.canonicalize(inputs);
        String hash = Sha256Hasher.hashBytes(canonical);
        Tree existing = trees.findByTreeHash(hash).orElse(null);
        if (existing != null) return existing;
        storage.write(hash, new ByteArrayInputStream(canonical), canonical.length);
        Tree tree = trees.saveAndFlush(new Tree(hash));
        List<TreeEntry> rows = new ArrayList<>();
        for (var child : directory.children.entrySet()) {
            if (child.getValue() instanceof Blob blob) rows.add(new TreeEntry(tree, child.getKey(), TreeEntryType.FILE, blob, null));
            else rows.add(new TreeEntry(tree, child.getKey(), TreeEntryType.DIRECTORY, null, childTrees.get(child.getKey())));
        }
        entries.saveAll(rows); entries.flush();
        return tree;
    }

    private void addFileDirectoryConflicts(Set<String> paths, Set<String> conflicts) {
        for (String path : paths) {
            int slash = path.indexOf('/');
            while (slash >= 0) {
                String parent = path.substring(0, slash);
                if (paths.contains(parent)) { conflicts.add(parent); conflicts.add(path); }
                slash = path.indexOf('/', slash + 1);
            }
        }
    }

    private String hash(Blob blob) { return blob == null ? null : blob.getObjectHash(); }
    private boolean same(String first, String second) { return java.util.Objects.equals(first, second); }

    public record Plan(Map<String, FileValue> files, List<String> conflictPaths) {
        public Plan { files = Map.copyOf(files); conflictPaths = List.copyOf(conflictPaths); }
    }
    public sealed interface FileValue permits ExistingFile, PendingFile { }
    public record ExistingFile(Blob blob) implements FileValue { }
    public record PendingFile(byte[] bytes) implements FileValue { public PendingFile { bytes = bytes.clone(); } }
    private static final class Directory { final Map<String, Object> children = new TreeMap<>(); }

    /** One contiguous edit per side; disjoint ranges combine, while ambiguous/overlapping edits conflict. */
    static final class TextMerge {
        static String merge(String baseText, String sourceText, String targetText) {
            if (sourceText.equals(targetText)) return sourceText;
            String[] base = lines(baseText), source = lines(sourceText), target = lines(targetText);
            Edit left = edit(base, source), right = edit(base, target);
            if (left == null || right == null) return null;
            if (left.start == right.start && left.end == right.end && left.replacement.equals(right.replacement)) return sourceText;
            if (overlaps(left, right)) return null;
            List<Edit> edits = new ArrayList<>(List.of(left, right));
            edits.sort(java.util.Comparator.comparingInt(e -> e.start));
            List<String> output = new ArrayList<>();
            int cursor = 0;
            for (Edit edit : edits) {
                output.addAll(Arrays.asList(base).subList(cursor, edit.start));
                output.addAll(edit.replacement);
                cursor = edit.end;
            }
            output.addAll(Arrays.asList(base).subList(cursor, base.length));
            return String.join("\n", output);
        }
        private static String[] lines(String value) { return value.split("\n", -1); }
        private static Edit edit(String[] base, String[] changed) {
            int prefix = 0;
            while (prefix < base.length && prefix < changed.length && base[prefix].equals(changed[prefix])) prefix++;
            int suffix = 0;
            while (suffix < base.length - prefix && suffix < changed.length - prefix
                    && base[base.length - 1 - suffix].equals(changed[changed.length - 1 - suffix])) suffix++;
            if (prefix == base.length && prefix == changed.length) return new Edit(0, 0, List.of());
            int end = base.length - suffix;
            int changedEnd = changed.length - suffix;
            return new Edit(prefix, end, List.of(Arrays.copyOfRange(changed, prefix, changedEnd)));
        }
        private static boolean overlaps(Edit first, Edit second) {
            if (first.start == first.end) return second.start <= first.start && first.start <= second.end;
            if (second.start == second.end) return first.start <= second.start && second.start <= first.end;
            return first.start < second.end && second.start < first.end;
        }
        private record Edit(int start, int end, List<String> replacement) { }
    }
}
