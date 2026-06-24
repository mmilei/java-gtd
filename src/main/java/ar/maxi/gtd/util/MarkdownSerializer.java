package ar.maxi.gtd.util;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MarkdownSerializer {

    private MarkdownSerializer() {}

    public static Map<String, Object> parse(String content) {
        if (!content.startsWith("---")) {
            return new LinkedHashMap<>(Map.of("body", content.strip()));
        }
        int firstNewline = content.indexOf('\n');
        if (firstNewline == -1) return new LinkedHashMap<>(Map.of("body", content.strip()));

        int secondMarker = content.indexOf("\n---", firstNewline + 1);
        if (secondMarker == -1) return new LinkedHashMap<>(Map.of("body", content.strip()));

        String yamlPart = content.substring(firstNewline + 1, secondMarker);

        int bodyStart = content.indexOf('\n', secondMarker + 1);
        String bodyPart = bodyStart == -1 ? "" : content.substring(bodyStart + 1).strip();

        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(yamlPart);
        if (map == null) map = new LinkedHashMap<>();
        map = new LinkedHashMap<>(map);
        map.put("body", bodyPart);
        return map;
    }

    public static String serialize(Map<String, Object> frontmatter, String body) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setPrettyFlow(true);
        Yaml yaml = new Yaml(opts);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append(yaml.dump(frontmatter));
        sb.append("---\n\n");
        if (body != null && !body.isBlank()) {
            sb.append(body).append('\n');
        }
        return sb.toString();
    }
}
