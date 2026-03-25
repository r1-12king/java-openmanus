package com.openmanus.tool;

import com.openmanus.schema.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络搜索工具，使用百度搜索（无需 API Key，国内可访问）
 * 对应 OpenManus 的 WebSearch
 */
@Slf4j
public class WebSearchTool implements BaseTool {

    /**
     * HTTP 客户端，连接超时 10s，读超时 15s
     */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search the web for information. Returns relevant results with titles and snippets.";
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(getName())
                .description(getDescription())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "The search query string")
                        .addIntegerProperty("max_results",
                                "Maximum number of results to return (default: 5)")
                        .required("query")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        int maxResults = args.containsKey("max_results")
                ? ((Number) args.get("max_results")).intValue() : 5;

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // 百度搜索 /s 端点，params: wd, rn, ie
            String url = "https://www.baidu.com/s"
                    + "?wd=" + encoded
                    + "&rn=" + (maxResults * 2)
                    + "&ie=utf-8";

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent",
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://www.baidu.com/")
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Baidu search [{}] status={}", query, response.code());
                    return ToolResult.failure("Search request failed with HTTP " + response.code());
                }

                String html = response.body().string();
                log.info("Baidu search [{}] html_len={}", query, html.length());

                List<SearchResult> results = parseBaiduHtml(html, maxResults);
                log.info("Baidu search [{}] -> {} results", query, results.size());

                if (results.isEmpty()) {
                    return ToolResult.success("No results found for: " + query);
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Search results for \"").append(query).append("\":\n\n");
                for (int i = 0; i < results.size(); i++) {
                    SearchResult r = results.get(i);
                    sb.append("[").append(i + 1).append("] ").append(r.title).append("\n");
                    if (!r.snippet.isBlank()) sb.append("   ").append(r.snippet).append("\n");
                    sb.append("   ").append(r.url).append("\n");
                    if (i < results.size() - 1) sb.append("\n");
                }
                return ToolResult.success(sb.toString());
            }
        } catch (Exception e) {
            log.error("Web search failed: {}", e.getMessage());
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }

    // ===== 解析逻辑（与 search.py 完全对齐） =====

    private static final Pattern AREA_PATTERN =
            Pattern.compile("id=\"content_left\"[^>]*>(.*?)</div>\\s*<div[^>]+id=\"content_right\"",
                    Pattern.DOTALL);

    private static final Pattern BLOCKS_PATTERN =
            Pattern.compile("(?=<div\\s[^>]*class=\"[^\"]*\\bresult\\b)", Pattern.DOTALL);

    private static final Pattern BLOCK_CLASS_PATTERN =
            Pattern.compile("<div\\s[^>]*class=\"[^\"]*\\bresult\\b");

    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<h3[^>]*>.*?<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    private static final Pattern SHOW_URL_PATTERN =
            Pattern.compile("<span[^>]*(?:class=\"c-showurl\"|data-showurl)[^>]*>(.*?)</span>",
                    Pattern.DOTALL);

    private static final Pattern C_ABSTRACT_PATTERN =
            Pattern.compile("class=\"c-abstract[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);

    private static final Pattern CONTENT_SPAN_PATTERN =
            Pattern.compile("<span[^>]*class=\"[^\"]*content[^\"]*\"[^>]*>(.*?)</span>", Pattern.DOTALL);

    private static final Pattern P_PATTERN =
            Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    private static final Pattern TAG_PATTERN =
            Pattern.compile("<[^>]+>");

    /**
     * 解析百度 HTML，提取搜索结果
     */
    private List<SearchResult> parseBaiduHtml(String html, int maxResults) {
        List<SearchResult> results = new ArrayList<>();

        // 1. 取主结果区域 content_left
        Matcher areaMatcher = AREA_PATTERN.matcher(html);
        String area;
        if (areaMatcher.find()) {
            area = areaMatcher.group(1);
        } else {
            // 备用：取整个 body
            Matcher bodyMatcher = Pattern.compile("<body[^>]*>(.*?)</body>", Pattern.DOTALL)
                    .matcher(html);
            area = bodyMatcher.find() ? bodyMatcher.group(1) : html;
        }

        // 2. 按结果块分割
        String[] blocks = BLOCKS_PATTERN.split(area);
        for (String block : blocks) {
            if (!BLOCK_CLASS_PATTERN.matcher(block).find()) continue;
            if (block.length() > 200 && block.contains("ec_pp")) continue;
            if (block.contains("data-tuiguang")) continue;

            // 标题：h3 > a
            Matcher titleMatcher = TITLE_PATTERN.matcher(block);
            if (!titleMatcher.find()) continue;
            String href = titleMatcher.group(1).strip();
            String title = stripTags(titleMatcher.group(2));
            if (title.isBlank()) continue;

            // URL：优先取 c-showurl
            String url;
            Matcher showUrlMatcher = SHOW_URL_PATTERN.matcher(block);
            if (showUrlMatcher.find()) {
                String show = stripTags(showUrlMatcher.group(1)).split("\\s")[0];
                url = show.startsWith("http") ? show : "https://" + show;
            } else {
                url = href;
            }
            if (!url.startsWith("http")) continue;

            // 摘要：c-abstract / content span / p
            String snippet = "";
            Matcher absMatcher = C_ABSTRACT_PATTERN.matcher(block);
            if (absMatcher.find()) {
                snippet = truncate(stripTags(absMatcher.group(1)), 200);
            } else {
                Matcher csMatcher = CONTENT_SPAN_PATTERN.matcher(block);
                if (csMatcher.find()) {
                    snippet = truncate(stripTags(csMatcher.group(1)), 200);
                } else {
                    Matcher pMatcher = P_PATTERN.matcher(block);
                    if (pMatcher.find()) {
                        snippet = truncate(stripTags(pMatcher.group(1)), 200);
                    }
                }
            }

            results.add(new SearchResult(title, url, snippet));
            if (results.size() >= maxResults) break;
        }

        return results;
    }

    private String stripTags(String html) {
        return TAG_PATTERN.matcher(html).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record SearchResult(String title, String url, String snippet) {}
}
