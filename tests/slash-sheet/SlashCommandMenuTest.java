package dev.pi.android.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public final class SlashCommandMenuTest {
    public static void main(String[] args) throws Exception {
        JSONObject data = new JSONObject();
        JSONArray commands = new JSONArray();
        commands.put(cmd("compact", "Compact the current context", "extension"));
        commands.put(cmd("fix-tests", "Fix failing tests", "prompt"));
        commands.put(cmd("skill:review", "Review the diff", "skill"));
        data.put("commands", commands);

        List<SlashCommand> parsed = SlashCommand.parseServerCommands(data);
        if (parsed.size() != 3) throw new AssertionError("parse size " + parsed.size());
        if (!"compact".equals(parsed.get(0).rawName)) throw new AssertionError("order must follow get_commands");
        if (!"Fix failing tests".equals(parsed.get(1).description)) {
            throw new AssertionError("description must be bridge text");
        }

        List<SlashCommand> catalog = SlashCommand.bindAppActions(parsed);
        if (catalog.size() != 3) {
            throw new AssertionError("overlay must not be injected, size=" + catalog.size());
        }
        for (SlashCommand c : catalog) {
            if ("model".equals(c.rawName) || "settings".equals(c.rawName) || "app".equals(c.source)) {
                throw new AssertionError("overlay-only command leaked into catalog: " + c.name);
            }
        }

        List<SlashCommand> empty = SlashCommand.filterCatalog(java.util.Collections.emptyList(), "/");
        if (!empty.isEmpty()) {
            throw new AssertionError("empty get_commands must not invent rows, got " + names(empty));
        }

        List<SlashCommand> slashOnly = SlashCommand.filterCatalog(catalog, "/");
        if (slashOnly.size() != 3) {
            throw new AssertionError("/ must keep full get_commands catalog in data, got " + slashOnly.size());
        }
        if (!slashOnly.get(0).rawName.equals("compact")
                || !slashOnly.get(1).rawName.equals("fix-tests")
                || !slashOnly.get(2).rawName.equals("skill:review")) {
            throw new AssertionError("empty prefix must keep bridge order: " + names(slashOnly));
        }

        List<SlashCommand> fix = SlashCommand.filterCatalog(catalog, "/fix");
        if (fix.size() != 1 || !"fix-tests".equals(fix.get(0).rawName)) {
            throw new AssertionError("/fix should hit bridge name, got " + names(fix));
        }

        List<SlashCommand> skill = SlashCommand.filterCatalog(catalog, "/sk");
        if (skill.size() != 1 || !"skill:review".equals(skill.get(0).rawName)) {
            throw new AssertionError("/sk should fuzzy-match skill name, got " + names(skill));
        }

        JSONObject withModel = new JSONObject();
        JSONArray mixed = new JSONArray();
        mixed.put(cmd("model", "Select a model", "extension"));
        mixed.put(cmd("compact", "Compact", "extension"));
        withModel.put("commands", mixed);
        List<SlashCommand> bound = SlashCommand.bindAppActions(SlashCommand.parseServerCommands(withModel));
        if (bound.size() != 2) throw new AssertionError("same-name overlay must not add extras");
        if (!"model".equals(bound.get(0).appAction)) {
            throw new AssertionError("same-name model should keep App intercept");
        }
        if (!"Select a model".equals(bound.get(0).description)) {
            throw new AssertionError("must keep bridge description, got " + bound.get(0).description);
        }

        SlashCommand overlay = SlashCommand.appActionFor("/settings");
        if (overlay == null || !"settings".equals(overlay.appAction)) {
            throw new AssertionError("typed /settings still intercepts");
        }
        if (SlashCommand.appActionFor("/") != null) {
            throw new AssertionError("bare / is not an overlay command");
        }

        int selected = 1;
        int hit = slashOnly.size();
        String title = String.format("命令  (%d/%d)", selected, hit);
        if (!"命令  (1/3)".equals(title)) throw new AssertionError("count format " + title);

        try {
            SlashCommand.filterCatalog(null, "/");
            throw new AssertionError("null catalog must throw");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("catalog")) throw expected;
        }
        System.out.println("SlashCommandMenuTest ok");
    }

    private static JSONObject cmd(String name, String description, String source) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("description", description);
        o.put("source", source);
        return o;
    }

    private static String names(List<SlashCommand> list) {
        StringBuilder sb = new StringBuilder();
        for (SlashCommand c : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.rawName);
        }
        return sb.toString();
    }
}
