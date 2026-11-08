package com.dsh.android.tools;

import android.content.Context;

import com.dsh.android.util.ImageStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 对齐上游 dsh-tool-fs 的 read_image（src/read-image.ts）：
 * PNG/JPEG/WebP/GIF，扩展名声明 + 魔数兜底，envelope 文本 + 一个图片块。
 * 图片块在本实现里通过 Tool.Result.imagePaths 交给 AgentLoop 投影成多模态 user 消息。
 */
public final class ReadImageTool implements Tool {

    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_EDGE = 1568;

    private final Context context;
    private final Path workspace;

    public ReadImageTool(Context context, Path workspace) {
        this.context = context.getApplicationContext();
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "read_image";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("file_path", new JSONObject().put("type", "string")
                .put("description", "Path to the image file, resolved by the filesystem backend."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("file_path"));
        return Tool.function("read_image",
                "Read a PNG/JPEG/WebP/GIF file and return the image itself. "
                        + "Large images are downscaled automatically; do not install image libraries "
                        + "or create thumbnails to inspect an image.", params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        return executeRich(argsJson).text;
    }

    @Override
    public Result executeRich(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String fp = o.optString("file_path", "");
        if (fp.trim().isEmpty()) return Result.of("Error: file_path must be a non-empty string");

        Path target = FsPaths.resolve(workspace, fp);
        String display = FsPaths.display(workspace, target);
        String ext = extension(fp);
        String declared = declaredMediaType(ext);
        if (declared == null && !ext.isEmpty()) {
            return Result.of("Error: cannot read \"" + fp + "\": the ." + ext
                    + " extension does not declare a supported image format; read_image accepts "
                    + "PNG/JPEG/WebP/GIF files, including extension-less files in those formats");
        }
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return Result.of("Error: The path " + display + " does not exist. Please provide a valid path.");
        }
        long size = Files.size(target);
        if (size > MAX_SOURCE_BYTES) {
            return Result.of("Error: cannot read \"" + display + "\": the image exceeds the "
                    + (MAX_SOURCE_BYTES / 1024 / 1024) + " MiB byte limit; downscale the image and read the smaller copy");
        }
        byte[] data = Files.readAllBytes(target);
        String media = declared != null ? declared : sniff(data);
        if (media == null) {
            return Result.of("Error: cannot read \"" + display + "\": the file content is not a supported "
                    + "image format; read_image accepts PNG/JPEG/WebP/GIF");
        }

        int[] src = ImageStore.dimensions(target.toString());
        int origW = src[0], origH = src[1];
        boolean scale = Math.max(origW, origH) > MAX_EDGE;
        String suffix = ext.isEmpty() ? suffixFor(media) : ext;
        File saved = ImageStore.saveBytes(context, data, suffix, MAX_EDGE);
        int[] dims = ImageStore.dimensions(saved.getAbsolutePath());
        String outMedia = ImageStore.mediaType(saved.getAbsolutePath());

        String scaled = "";
        if (scale && dims[0] > 0 && dims[1] > 0) {
            double x = round2((double) origW / dims[0]);
            double y = round2((double) origH / dims[1]);
            String advice = x == y ? "multiply coordinates by " + x
                    : "multiply x coordinates by " + x + " and y coordinates by " + y;
            scaled = " (downscaled from " + origW + "x" + origH + " px; " + advice
                    + " to locate features in the original file)";
        }
        String text = "<path>" + display + "</path>\n<type>image</type>\n<content>\n"
                + outMedia + " image, " + dims[0] + "x" + dims[1] + " px, " + saved.length()
                + " bytes" + scaled + "\n</content>";
        List<String> paths = new ArrayList<>();
        paths.add(saved.getAbsolutePath());
        return new Result(text, paths);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash + 1 || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String declaredMediaType(String ext) {
        switch (ext) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            default: return null;
        }
    }

    private static String suffixFor(String media) {
        if ("image/png".equals(media)) return "png";
        if ("image/webp".equals(media)) return "webp";
        if ("image/gif".equals(media)) return "gif";
        return "jpg";
    }

    /** 无扩展名时按文件签名识别（官方 bug-fix 2026-08-28 的语义）。 */
    private static String sniff(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return "image/png";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8
                && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 6) {
            String head = new String(d, 0, 6, StandardCharsets.US_ASCII);
            if (head.equals("GIF87a") || head.equals("GIF89a")) return "image/gif";
        }
        if (d.length >= 12
                && "RIFF".equals(new String(d, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(d, 8, 4, StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        return null;
    }
}
