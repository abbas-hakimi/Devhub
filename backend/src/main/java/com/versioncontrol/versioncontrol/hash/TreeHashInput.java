package com.versioncontrol.versioncontrol.hash;

import com.versioncontrol.entity.TreeEntryType;

/** Persistence-independent data needed to hash one tree entry. */
public record TreeHashInput(String name, TreeEntryType type, String targetHash) { }
