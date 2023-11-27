package com.holeyko;

import com.holeyko.dao.Config;
import com.holeyko.dao.LSMDao;
import com.holeyko.entry.BaseEntry;
import com.holeyko.dao.Dao;
import com.holeyko.entry.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;

public class Main {
    private static final Path path = Path.of("data/");

    public static void main(String[] args) throws Exception {
        System.out.println("Start Main...");
        clearPath();

        var dao = getDao();
        dao.upsert(makeEntry("a", "b"));
        dao.upsert(makeEntry("c", "d"));
        dao.close();

        dao = getDao();
        dao.upsert(makeEntry("a", null));
        dao.upsert(makeEntry("hello", "world"));
        dao.upsert(makeEntry("nice", "life"));
        dao.close();

        dao = getDao();
        dao.compact();
        Iterator<Entry<MemorySegment>> it = dao.all();
        while (it.hasNext()) {
            printEntry(it.next());
        }
    }
    
    static MemorySegment transform(String s) {
        if (s == null) {
            return null;
        }
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }
    
    static String transform(MemorySegment segment) {
        if (segment == null) {
            return null;
        }
        return new String(segment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }
    
    static Dao<MemorySegment, Entry<MemorySegment>> getDao() throws IOException {
        return new LSMDao(new Config(path, Long.MAX_VALUE));
    }
    
    static Entry<MemorySegment> makeEntry(String key, String val) {
        return new BaseEntry<>(transform(key), transform(val));
    }
    
    static void printEntry(Entry<MemorySegment> entry) {
        if (entry == null) {
            System.out.println(entry);
            return;
        }
        
        System.out.println(transform(entry.key()) + ": " + transform(entry.value()));
    }
    
    static void clearPath() throws IOException {
        if (Files.notExists(path)) {
            Files.createDirectories(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
