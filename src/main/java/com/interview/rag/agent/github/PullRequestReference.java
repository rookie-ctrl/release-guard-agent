package com.interview.rag.agent.github;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PullRequestReference(String owner, String repository, int number) {
    private static final Pattern PATH = Pattern.compile("^/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/([1-9][0-9]*)(?:/(?:files|commits))?/?$");

    public static PullRequestReference parse(String url) {
        try {
            URI uri = URI.create(url.trim());
            Matcher matcher = PATH.matcher(uri.getPath());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || !matcher.matches()
                    || matcher.group(1).equals(".") || matcher.group(1).equals("..")
                    || matcher.group(2).equals(".") || matcher.group(2).equals("..")) {
                throw new IllegalArgumentException();
            }
            return new PullRequestReference(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
        } catch (Exception e) {
            throw new IllegalArgumentException("请输入有效的 GitHub PR 链接，例如 https://github.com/owner/repo/pull/123");
        }
    }

    public String url() {
        return "https://github.com/" + owner + "/" + repository + "/pull/" + number;
    }

    public String apiPath() {
        return "/repos/" + owner + "/" + repository + "/pulls/" + number;
    }
}
