package ar.maxi.gtd.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class MarkdownSerializer {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSerializer.class);
    private static final Pattern ISO_TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T.*");

    private MarkdownSerializer() {}

    public static Map<String, Object> parse(String content) {
        return parse(content, "unknown");
    }

    public static Map<String, Object> parse(String content, String sourceHint) {
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
        Map<String, Object> map;
        try {
            map = yaml.load(yamlPart);
        } catch (YAMLException e) {
            log.warn("Malformed YAML in [{}], using empty frontmatter: {}", sourceHint, e.getMessage());
            map = null;
        }
        if (map == null) map = new LinkedHashMap<>();
        map = new LinkedHashMap<>(map);
        // SnakeYAML parses unquoted YYYY-MM-DD as java.util.Date — normalize to string
        map.replaceAll((k, v) -> normalizeDate(v));
        map.put("body", bodyPart);
        return map;
    }

    private static Object normalizeDate(Object v) {
        if (v instanceof Date d) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(d.toInstant().atOffset(ZoneOffset.UTC));
        }
        if (v instanceof String s && ISO_TIMESTAMP.matcher(s).matches()) {
            return s.substring(0, 10);
        }
        return v;
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
