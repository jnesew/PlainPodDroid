package io.github.jnesew.plainpoddroid.core.chapter;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative local fallback. No URL, HTML, network or publisher inference. */
public final class NotesChapterParser {
    private static final Pattern LINE = Pattern.compile("^\\s*(?:\\(([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)\\)|([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?))\\s+(?:[-–—]\\s*)?(.+)$");
    private static final Pattern HEADER = Pattern.compile("(?:^|\\s)(?:outline|chapters|timestamps)\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLAT = Pattern.compile("\\(([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)\\)\\s*[-–—]\\s*");
    private static final Pattern TITLE_TIME = Pattern.compile("\\([0-9]{1,4}:");
    private static final Pattern TIME_LIKE = Pattern.compile("^\\s*\\(?[0-9]{1,4}:");
    private record Item(long start, String title) { }

    public PodcastChapterDocument parse(String notes, long duration) {
        if (notes == null || notes.length() > SecurityLimits.MAX_PLAIN_NOTES_CHARS
                || duration < -1 || duration > SecurityLimits.MAX_MEDIA_DURATION_MILLIS) return null;
        List<List<Item>> candidates = new ArrayList<>(); List<Item> block = new ArrayList<>(); boolean invalid = false;
        for (String line : notes.split("\\R", -1)) {
            if (line.isBlank()) continue;
            Matcher match = LINE.matcher(line);
            if (match.matches()) {
                Item item = item(match.group(1) == null ? match.group(2) : match.group(1), match.group(3), duration);
                if (item == null || block.size() >= SecurityLimits.MAX_CHAPTERS
                        || !block.isEmpty() && item.start() <= block.get(block.size()-1).start()) invalid = true;
                else block.add(item);
            } else {
                if (TIME_LIKE.matcher(line).find()) return null;
                if (!invalid && block.size() >= 3) candidates.add(block);
                block = new ArrayList<>(); invalid = false;
            }
            if (invalid) return null;
        }
        if (!invalid && block.size() >= 3) candidates.add(block);
        if (candidates.size() == 1) return document(candidates.get(0), duration);
        if (!candidates.isEmpty()) return null;
        // Older saved notes collapsed line breaks; require an explicit outline heading.
        Matcher header = HEADER.matcher(notes);
        if (!header.find()) return null;
        int start = header.end(); if (header.find()) return null;
        String outline = notes.substring(start).trim(); Matcher times = FLAT.matcher(outline);
        List<Item> items = new ArrayList<>(); String previous = null; int titleStart = -1;
        while (times.find()) {
            if (titleStart < 0 && !outline.substring(0,times.start()).isBlank()) return null;
            if (previous != null) {
                Item item = item(previous, outline.substring(titleStart,times.start()), duration);
                if (!append(items,item)) return null;
            }
            previous = times.group(1); titleStart = times.end();
        }
        if (previous == null || !append(items,item(previous,outline.substring(titleStart),duration)) || items.size() < 3) return null;
        return document(items,duration);
    }
    private static boolean append(List<Item> items, Item item) {
        if (item == null || items.size() >= SecurityLimits.MAX_CHAPTERS
                || !items.isEmpty() && item.start() <= items.get(items.size()-1).start()) return false;
        items.add(item); return true;
    }
    private static Item item(String time, String rawTitle, long duration) {
        String[] fields = time.split(":"); int a = Integer.parseInt(fields[0]), b = Integer.parseInt(fields[1]);
        int c = fields.length == 3 ? Integer.parseInt(fields[2]) : 0;
        if (b > 59 || c > 59) return null;
        long millis = (fields.length == 3 ? a * 3600L + b * 60L + c : a * 60L + b) * 1000;
        if (TITLE_TIME.matcher(rawTitle).find()) return null;
        String title = rawTitle.trim().replaceAll("\\s+", " ");
        if (title.isEmpty() || title.length() > SecurityLimits.MAX_CHAPTER_TITLE_CHARS
                || millis > SecurityLimits.MAX_MEDIA_DURATION_MILLIS || duration >= 0 && millis >= duration) return null;
        return new Item(millis,title);
    }
    private static PodcastChapterDocument document(List<Item> items, long duration) {
        List<PodcastChapter> chapters = new ArrayList<>(items.size());
        for (int i=0;i<items.size();i++) {
            Item item = items.get(i); long end = i+1<items.size() ? items.get(i+1).start() : duration;
            chapters.add(new PodcastChapter(item.start(),end,item.title(),null,null,true));
        }
        return new PodcastChapterDocument("notes",chapters);
    }
}
