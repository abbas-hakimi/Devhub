package com.versioncontrol.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.text.Normalizer;

import org.springframework.stereotype.Service;

import com.versioncontrol.dto.diff.FileChangeResponse;
import com.versioncontrol.entity.Blob;
import com.versioncontrol.entity.Tree;
import com.versioncontrol.entity.TreeEntry;
import com.versioncontrol.entity.TreeEntryType;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.repository.TreeEntryRepository;
import com.versioncontrol.versioncontrol.storage.ObjectStorage;
import com.versioncontrol.entity.ReviewCommentSide;
import com.versioncontrol.validation.TreeEntryNameValidator;
import com.versioncontrol.exception.InvalidRequestException;

@Service
public class FileDiffService {
    private static final int MAX_CHANGED_FILES = 2_000;
    private static final int MAX_TREE_VISITS = 100_000;
    private static final int MAX_TREE_DEPTH = 64;
    private static final int MAX_TEXT_BLOB_BYTES = 128 * 1024;
    private static final int MAX_SNIFF_BYTES = 8 * 1024;
    private static final int MAX_DIFF_LINES = 1_200;
    private static final long MAX_LCS_CELLS = 1_000_000;
    private static final int MAX_PATCH_BYTES = 48 * 1024;
    private static final int MAX_RESPONSE_PATCH_BYTES = 512 * 1024;
    private static final int CONTEXT_LINES = 3;

    private final TreeEntryRepository entries;
    private final ObjectStorage storage;

    public FileDiffService(TreeEntryRepository entries, ObjectStorage storage) {
        this.entries = entries;
        this.storage = storage;
    }

    public Result compare(Tree baseTree, Tree headTree, int page, int size) {
        Collector collector = new Collector();
        walk(baseTree, headTree, "", 0, collector);
        collector.changes.sort(Comparator.comparing(Change::path, FileDiffService::compareUtf8));
        int from = Math.min(collector.changes.size(), Math.toIntExact(Math.min((long) page * size, Integer.MAX_VALUE)));
        int to = Math.min(collector.changes.size(), from + size);
        List<FileChangeResponse> pageItems = new ArrayList<>();
        int responseBytes = 0;
        for (Change change : collector.changes.subList(from, to)) {
            Prepared prepared = prepare(change);
            int payloadBytes = prepared.payloadBytes;
            if (payloadBytes > 0 && responseBytes + payloadBytes > MAX_RESPONSE_PATCH_BYTES) {
                pageItems.add(metadata(change, prepared.binary, null, 0, 0, true));
            } else {
                responseBytes += payloadBytes;
                pageItems.add(render(change, prepared));
            }
        }
        int added = (int) collector.changes.stream().filter(c -> c.status.equals("ADDED")).count();
        int modified = (int) collector.changes.stream().filter(c -> c.status.equals("MODIFIED")).count();
        int deleted = (int) collector.changes.stream().filter(c -> c.status.equals("DELETED")).count();
        int additions = pageItems.stream().mapToInt(FileChangeResponse::additions).sum();
        int deletions = pageItems.stream().mapToInt(FileChangeResponse::deletions).sum();
        return new Result(List.copyOf(pageItems), collector.changes.size(), added, modified, deleted,
                additions, deletions, collector.truncated);
    }

    public void validateInlineComment(Tree baseTree, Tree headTree, String path, int lineNumber, ReviewCommentSide side) {
        if (path == null || path.isBlank() || path.length() > 1024 || path.startsWith("/") || path.endsWith("/")) {
            throw new InvalidRequestException("Inline comment path is invalid");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (!TreeEntryNameValidator.isValidName(segment)
                    || !Normalizer.isNormalized(segment, Normalizer.Form.NFC)) {
                throw new InvalidRequestException("Inline comment path is invalid");
            }
        }
        Blob oldBlob = resolveFile(baseTree, segments);
        Blob newBlob = resolveFile(headTree, segments);
        if (oldBlob == null && newBlob == null) throw new InvalidRequestException("Inline comment path is not a file in the pull request diff");
        if (oldBlob != null && newBlob != null && oldBlob.getObjectHash().equals(newBlob.getObjectHash())) {
            throw new InvalidRequestException("Inline comment path is not changed in the pull request diff");
        }
        Blob selected = side == ReviewCommentSide.OLD ? oldBlob : newBlob;
        if (selected == null) throw new InvalidRequestException("Inline comment side does not exist for this file");
        ReadResult content = read(selected);
        if (content.binary || content.omitted) throw new InvalidRequestException("Inline comments require a supported text file");
        int lines = lines(content.text).lines.size();
        if (lineNumber < 1 || lineNumber > lines) throw new InvalidRequestException("Inline comment line is outside the selected file version");
    }

    private Blob resolveFile(Tree root, String[] segments) {
        Tree current = root;
        for (int i = 0; i < segments.length; i++) {
            TreeEntry entry = entries.findByTree_IdAndName(current.getId(), segments[i]).orElse(null);
            if (entry == null) return null;
            boolean last = i == segments.length - 1;
            if (last) return entry.getEntryType() == TreeEntryType.FILE ? entry.getBlob() : null;
            if (entry.getEntryType() != TreeEntryType.DIRECTORY) return null;
            current = entry.getChildTree();
        }
        return null;
    }

    private void walk(Tree base, Tree head, String prefix, int depth, Collector collector) {
        if (base.getTreeHash().equals(head.getTreeHash()) || collector.truncated) return;
        if (depth > MAX_TREE_DEPTH) throw new RepositoryConflictException("Stored tree exceeds the supported nesting depth");
        if (!collector.visit()) return;
        Map<String, TreeEntry> oldEntries = byName(entries.findByTree_IdOrderByNameAsc(base.getId()));
        Map<String, TreeEntry> newEntries = byName(entries.findByTree_IdOrderByNameAsc(head.getId()));
        Set<String> names = new HashSet<>(oldEntries.keySet());
        names.addAll(newEntries.keySet());
        List<String> ordered = new ArrayList<>(names);
        ordered.sort(FileDiffService::compareUtf8);
        for (String name : ordered) {
            if (collector.truncated) return;
            TreeEntry oldEntry = oldEntries.get(name);
            TreeEntry newEntry = newEntries.get(name);
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (oldEntry == null) {
                collect(newEntry, path, "ADDED", collector, depth + 1);
            } else if (newEntry == null) {
                collect(oldEntry, path, "DELETED", collector, depth + 1);
            } else if (oldEntry.getEntryType() == TreeEntryType.FILE && newEntry.getEntryType() == TreeEntryType.FILE) {
                if (!oldEntry.getBlob().getObjectHash().equals(newEntry.getBlob().getObjectHash())) {
                    collector.add(new Change(path, "MODIFIED", oldEntry.getBlob(), newEntry.getBlob()));
                }
            } else if (oldEntry.getEntryType() == TreeEntryType.DIRECTORY && newEntry.getEntryType() == TreeEntryType.DIRECTORY) {
                if (!oldEntry.getChildTree().getTreeHash().equals(newEntry.getChildTree().getTreeHash())) {
                    walk(oldEntry.getChildTree(), newEntry.getChildTree(), path, depth + 1, collector);
                }
            } else if (oldEntry.getEntryType() == TreeEntryType.FILE) {
                collector.add(new Change(path, "DELETED", oldEntry.getBlob(), null));
                collect(newEntry, path, "ADDED", collector, depth + 1);
            } else {
                collect(oldEntry, path, "DELETED", collector, depth + 1);
                collector.add(new Change(path, "ADDED", null, newEntry.getBlob()));
            }
        }
    }

    private void collect(TreeEntry entry, String path, String status, Collector collector, int depth) {
        if (collector.truncated) return;
        if (entry.getEntryType() == TreeEntryType.FILE) {
            collector.add(status.equals("ADDED")
                    ? new Change(path, status, null, entry.getBlob())
                    : new Change(path, status, entry.getBlob(), null));
            return;
        }
        if (depth > MAX_TREE_DEPTH) throw new RepositoryConflictException("Stored tree exceeds the supported nesting depth");
        if (!collector.visit()) return;
        for (TreeEntry child : entries.findByTree_IdOrderByNameAsc(entry.getChildTree().getId())) {
            if (collector.truncated) return;
            String childPath = path + "/" + child.getName();
            collect(child, childPath, status, collector, depth + 1);
        }
    }

    private Prepared prepare(Change change) {
        ReadResult old = change.oldBlob == null ? null : read(change.oldBlob);
        ReadResult current = change.newBlob == null ? null : read(change.newBlob);
        boolean binary = (old != null && old.binary) || (current != null && current.binary);
        int payload = (old == null ? 0 : old.bytesRead) + (current == null ? 0 : current.bytesRead);
        if (binary) return new Prepared(true, null, false, payload);
        if ((old != null && old.omitted) || (current != null && current.omitted)) return new Prepared(false, null, true, payload);
        String oldText = old == null ? "" : old.text;
        String newText = current == null ? "" : current.text;
        Patch patch = unified(change.path, oldText, newText);
        if (patch == null || patch.text.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) {
            return new Prepared(false, null, true, payload);
        }
        return new Prepared(false, patch, false, payload);
    }

    private ReadResult read(Blob blob) {
        storage.verify(blob.getObjectHash(), blob.getSize());
        if (blob.getSize() > MAX_TEXT_BLOB_BYTES) {
            try (var input = storage.read(blob.getObjectHash())) {
                byte[] sample = input.readNBytes(MAX_SNIFF_BYTES);
                boolean binary = hasNul(sample) || !validUtf8Prefix(sample);
                return new ReadResult(binary, true, null, sample.length);
            } catch (IOException io) {
                throw new RepositoryConflictException("Unable to read a stored blob for comparison");
            }
        }
        try (var input = storage.read(blob.getObjectHash())) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length != blob.getSize()) throw new RepositoryConflictException("Stored blob size does not match its metadata");
            if (hasNul(bytes)) return new ReadResult(true, false, null, bytes.length);
            String text = decode(bytes);
            return text == null ? new ReadResult(true, false, null, bytes.length)
                    : new ReadResult(false, false, normalizeNewlines(text), bytes.length);
        } catch (IOException io) {
            throw new RepositoryConflictException("Unable to read a stored blob for comparison");
        }
    }

    private FileChangeResponse render(Change change, Prepared prepared) {
        if (prepared.patch == null) return metadata(change, prepared.binary, null, 0, 0, prepared.truncated);
        return metadata(change, false, prepared.patch.text, prepared.patch.additions, prepared.patch.deletions, false);
    }

    private FileChangeResponse metadata(Change change, boolean binary, String patch, int additions, int deletions, boolean truncated) {
        return new FileChangeResponse(change.path, change.status,
                change.oldBlob == null ? null : change.oldBlob.getObjectHash(),
                change.newBlob == null ? null : change.newBlob.getObjectHash(),
                binary, patch, additions, deletions, truncated);
    }

    private Patch unified(String path, String oldText, String newText) {
        TextLines oldLines = lines(oldText);
        TextLines newLines = lines(newText);
        int n = oldLines.lines.size();
        int m = newLines.lines.size();
        if (n > MAX_DIFF_LINES || m > MAX_DIFF_LINES || (long) (n + 1) * (m + 1) > MAX_LCS_CELLS) return null;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.lines.get(i).equals(newLines.lines.get(j))
                        ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>(n + m);
        int i = 0, j = 0, additions = 0, deletions = 0;
        while (i < n || j < m) {
            if (i < n && j < m && oldLines.lines.get(i).equals(newLines.lines.get(j))) {
                ops.add(new Op(' ', oldLines.lines.get(i++)));
                j++;
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                ops.add(new Op('+', newLines.lines.get(j++)));
                additions++;
            } else {
                ops.add(new Op('-', oldLines.lines.get(i++)));
                deletions++;
            }
        }
        boolean newlineOnly = ops.stream().noneMatch(op -> op.kind != ' ')
                && oldLines.finalNewline != newLines.finalNewline;
        if (ops.isEmpty() && newlineOnly) ops.add(new Op(' ', ""));
        String patch = formatPatch(path, ops, oldLines.finalNewline, newLines.finalNewline);
        return new Patch(patch, additions, deletions);
    }

    private String formatPatch(String path, List<Op> ops, boolean oldFinalNewline, boolean newFinalNewline) {
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(ops.stream().anyMatch(o -> o.kind == '-' ) ? "a/" + path : "/dev/null").append('\n');
        out.append("+++ ").append(ops.stream().anyMatch(o -> o.kind == '+') ? "b/" + path : "/dev/null").append('\n');
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) if (ops.get(i).kind != ' ') changed.add(i);
        List<int[]> ranges = new ArrayList<>();
        for (int index : changed) {
            int start = Math.max(0, index - CONTEXT_LINES);
            int end = Math.min(ops.size(), index + CONTEXT_LINES + 1);
            if (!ranges.isEmpty() && start <= ranges.get(ranges.size() - 1)[1]) ranges.get(ranges.size() - 1)[1] = end;
            else ranges.add(new int[] {start, end});
        }
        if (ranges.isEmpty() && !ops.isEmpty()) ranges.add(new int[] {0, ops.size()});
        if (ranges.isEmpty() && (oldFinalNewline != newFinalNewline)) ranges.add(new int[] {0, 0});
        for (int[] range : ranges) {
            int oldBefore = 0, newBefore = 0;
            for (int k = 0; k < range[0]; k++) {
                if (ops.get(k).kind != '+') oldBefore++;
                if (ops.get(k).kind != '-') newBefore++;
            }
            int oldCount = 0, newCount = 0;
            for (int k = range[0]; k < range[1]; k++) {
                if (ops.get(k).kind != '+') oldCount++;
                if (ops.get(k).kind != '-') newCount++;
            }
            int oldStart = oldCount == 0 ? oldBefore : oldBefore + 1;
            int newStart = newCount == 0 ? newBefore : newBefore + 1;
            out.append("@@ -").append(oldStart).append(',').append(oldCount)
                    .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            for (int k = range[0]; k < range[1]; k++) out.append(ops.get(k).kind).append(ops.get(k).line).append('\n');
        }
        if (!oldFinalNewline || !newFinalNewline) out.append("\\ No newline at end of file\n");
        return out.toString();
    }

    private static TextLines lines(String text) {
        if (text.isEmpty()) return new TextLines(List.of(), false);
        boolean newline = text.endsWith("\n");
        String[] raw = text.split("\n", -1);
        if (newline) raw = Arrays.copyOf(raw, raw.length - 1);
        return new TextLines(Arrays.asList(raw), newline);
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    private static boolean validUtf8Prefix(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(bytes.length + 4);
        return !decoder.decode(input, output, false).isError();
    }

    private static boolean hasNul(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private static Map<String, TreeEntry> byName(List<TreeEntry> entries) {
        Map<String, TreeEntry> result = new HashMap<>();
        for (TreeEntry entry : entries) result.put(entry.getName(), entry);
        return result;
    }

    public static int compareUtf8(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int result = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (result != 0) return result;
        }
        return Integer.compare(a.length, b.length);
    }

    private final class Collector {
        private final List<Change> changes = new ArrayList<>();
        private int visits;
        private boolean truncated;
        private boolean visit() {
            if (++visits > MAX_TREE_VISITS) { truncated = true; return false; }
            return true;
        }
        private void add(Change change) {
            if (changes.size() >= MAX_CHANGED_FILES) { truncated = true; return; }
            changes.add(change);
        }
    }

    public record Result(List<FileChangeResponse> changes, int filesChanged, int filesAdded,
            int filesModified, int filesDeleted, int additions, int deletions, boolean filesTruncated) { }
    private record Change(String path, String status, Blob oldBlob, Blob newBlob) { }
    private record ReadResult(boolean binary, boolean omitted, String text, int bytesRead) { }
    private record Prepared(boolean binary, Patch patch, boolean truncated, int payloadBytes) { }
    private record Patch(String text, int additions, int deletions) { }
    private record TextLines(List<String> lines, boolean finalNewline) { }
    private record Op(char kind, String line) { }
}
