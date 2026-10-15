package dev.pi.android.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        Map<String, SlashCommand> overlay = SlashCommand.appOverlay();
        List<String> overlayNames = new ArrayList<>(overlay.keySet());
        if (!overlayNames.contains("model")) {
            throw new AssertionError("appOverlay must include model");
        }

        List<SlashCommand> catalog = SlashCommand.bindAppActions(parsed);
        int expectedSize = overlay.size() + parsed.size();
        if (catalog.size() != expectedSize) {
            throw new AssertionError("merged size expected " + expectedSize + " got " + catalog.size()
                    + ": " + names(catalog));
        }
        for (int i = 0; i < overlayNames.size(); i++) {
            SlashCommand row = catalog.get(i);
            String want = overlayNames.get(i);
            if (!want.equals(row.rawName)) {
                throw new AssertionError("builtins first at " + i + ", expected " + want + " got " + names(catalog));
            }
            if (row.appAction == null || row.appAction.isEmpty()) {
                throw new AssertionError("overlay " + row.name + " must keep appAction");
            }
        }
        if (!hasRaw(catalog, "model") || !hasRaw(catalog, "settings")) {
            throw new AssertionError("empty / catalog must include overlay builtins, got " + names(catalog));
        }
        if (!hasRaw(catalog, "compact") || !hasRaw(catalog, "fix-tests") || !hasRaw(catalog, "skill:review")) {
            throw new AssertionError("server-only commands must remain, got " + names(catalog));
        }
        int serverStart = overlay.size();
        if (!"compact".equals(catalog.get(serverStart).rawName)
                || !"fix-tests".equals(catalog.get(serverStart + 1).rawName)
                || !"skill:review".equals(catalog.get(serverStart + 2).rawName)) {
            throw new AssertionError("non-duplicate server cmds follow overlay: " + names(catalog));
        }

        List<SlashCommand> filterEmpty = SlashCommand.filterCatalog(java.util.Collections.emptyList(), "/");
        if (!filterEmpty.isEmpty()) {
            throw new AssertionError("filterCatalog must not invent rows, got " + names(filterEmpty));
        }

        List<SlashCommand> noServer = SlashCommand.bindAppActions(java.util.Collections.emptyList());
        if (noServer.size() != overlay.size() || !hasRaw(noServer, "model")) {
            throw new AssertionError("empty get_commands still lists App builtins, got " + names(noServer));
        }

        List<SlashCommand> slashOnly = SlashCommand.filterCatalog(catalog, "/");
        if (slashOnly.size() != catalog.size()) {
            throw new AssertionError("/ must show full merged catalog, got " + slashOnly.size());
        }
        if (!hasRaw(slashOnly, "model")) {
            throw new AssertionError("empty / must include model, got " + names(slashOnly));
        }
        if (!slashOnly.get(0).rawName.equals("model")) {
            throw new AssertionError("empty / overlay order starts with model: " + names(slashOnly));
        }

        List<SlashCommand> modelHits = SlashCommand.filterCatalog(catalog, "/model");
        if (modelHits.isEmpty()) {
            throw new AssertionError("/model must be non-empty");
        }
        SlashCommand modelHit = null;
        for (SlashCommand c : modelHits) {
            if ("model".equals(c.rawName)) modelHit = c;
        }
        if (modelHit == null || !"model".equals(modelHit.appAction)) {
            throw new AssertionError("/model must match overlay builtin, got " + names(modelHits));
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
        if (bound.size() != overlay.size() + 1) {
            throw new AssertionError("same-name must not duplicate, size=" + bound.size() + " " + names(bound));
        }
        SlashCommand mergedModel = bound.get(0);
        if (!"model".equals(mergedModel.rawName) || !"model".equals(mergedModel.appAction)) {
            throw new AssertionError("same-name model should keep App intercept");
        }
        if (!"Select a model".equals(mergedModel.description)) {
            throw new AssertionError("must keep bridge description, got " + mergedModel.description);
        }
        if (!"extension".equals(mergedModel.source)) {
            throw new AssertionError("same-name keeps server source, got " + mergedModel.source);
        }
        if (!hasRaw(bound, "compact")) {
            throw new AssertionError("server-only compact must remain, got " + names(bound));
        }

        JSONObject noDesc = new JSONObject();
        JSONArray noDescArr = new JSONArray();
        noDescArr.put(cmd("model", "", "extension"));
        noDesc.put("commands", noDescArr);
        List<SlashCommand> overlayDesc = SlashCommand.bindAppActions(SlashCommand.parseServerCommands(noDesc));
        if (!"切换模型".equals(overlayDesc.get(0).description)) {
            throw new AssertionError("missing server desc uses overlay, got " + overlayDesc.get(0).description);
        }

        SlashCommand overlayCmd = SlashCommand.appActionFor("/settings");
        if (overlayCmd == null || !"settings".equals(overlayCmd.appAction)) {
            throw new AssertionError("typed /settings still intercepts");
        }
        if (SlashCommand.appActionFor("/") != null) {
            throw new AssertionError("bare / is not an overlay command");
        }

        int selected = 1;
        int hit = slashOnly.size();
        String title = String.format("命令  (%d/%d)", selected, hit);
        String expectedTitle = String.format("命令  (1/%d)", expectedSize);
        if (!expectedTitle.equals(title)) throw new AssertionError("count format " + title);

        JSONArray cached = SlashCommand.toCacheJson(parsed);
        String cacheJson = cached.toString();
        if (cacheJson.contains("\"source\":\"app\"") || hasCachedName(cached, "model")) {
            throw new AssertionError("cache must not persist overlay-only, got " + cacheJson);
        }
        List<SlashCommand> fromCache = SlashCommand.fromCacheJson(cacheJson);
        if (fromCache.size() != parsed.size() || hasRaw(fromCache, "model")) {
            throw new AssertionError("fromCache is server-only, got " + names(fromCache));
        }
        List<SlashCommand> remade = SlashCommand.bindAppActions(fromCache);
        if (!hasRaw(remade, "model") || !hasRaw(remade, "compact")) {
            throw new AssertionError("merge rebuilds overlay in memory, got " + names(remade));
        }
        try {
            SlashCommand.toCacheJson(java.util.Collections.singletonList(overlay.get("model")));
            throw new AssertionError("overlay-only must not be cached");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("overlay-only")) throw expected;
        }

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

    private static boolean hasRaw(List<SlashCommand> list, String raw) {
        for (SlashCommand c : list) {
            if (raw.equals(c.rawName)) return true;
        }
        return false;
    }

    private static boolean hasCachedName(JSONArray arr, String raw) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            if (raw.equals(arr.getJSONObject(i).optString("name"))) return true;
        }
        return false;
    }
}
