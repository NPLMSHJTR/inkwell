package inkwell;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HTTP front end: /search and /stats. */
public final class Server {
    private static Engine engine;

    public static void main(String[] args) throws IOException {
        Path documents = Path.of("data/documents.tsv");
        int port = 8080;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--docs" -> documents = Path.of(args[++index]);
                case "--port" -> port = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException("unknown argument " + args[index]);
            }
        }
        engine = new Engine();
        engine.build(documents);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", Server::page);
        server.createContext("/search", Server::search);
        server.createContext("/stats", Server::stats);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("inkwell listening on http://127.0.0.1:" + port);
    }


    private static final String PAGE = """
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>inkwell</title>
        <style>
          :root{--bg:#0b1020;--panel:#121a2c;--line:#26314a;--text:#e6ecf6;--muted:#8fa0b8;--accent:#60a5fa}
          *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);
            font:15px/1.6 ui-sans-serif,system-ui,"Segoe UI",sans-serif}
          header{padding:28px 24px 10px}h1{margin:0 0 4px;font-size:20px}
          .sub{color:var(--muted);font-size:13px}
          .bar{display:flex;gap:10px;padding:18px 24px;position:sticky;top:0;background:var(--bg);
            border-bottom:1px solid var(--line)}
          input{flex:1;padding:12px 14px;border-radius:8px;border:1px solid var(--line);
            background:var(--panel);color:var(--text);font-size:15px;outline:none}
          input:focus{border-color:var(--accent)}
          button{padding:12px 18px;border-radius:8px;border:0;background:var(--accent);color:#06101f;
            font-weight:600;cursor:pointer}
          main{padding:18px 24px;max-width:900px}
          .hit{padding:14px 0;border-bottom:1px solid var(--line)}
          .hit h2{margin:0 0 4px;font-size:16px;color:#bfdbfe;font-weight:600}
          .hit .meta{color:var(--muted);font-size:12px;margin-bottom:6px}
          .hit p{margin:0;color:#c9d4e6}
          .empty{color:var(--muted);padding:30px 0}
          mark{background:#1e3a8a;color:#dbeafe;border-radius:2px;padding:0 2px}
        </style></head><body>
        <header><h1>inkwell</h1>
        <div class="sub">positional index · BM25 · phrase queries · 50,000 documents</div></header>
        <div class="bar"><input id="q" placeholder="try: t0007 t0012 · &quot;t0007 t0012&quot; · t0007 -c0031" autofocus>
        <button id="go">Search</button></div>
        <main id="out"><div class="empty">Type a query and press Enter.</div></main>
        <script>
          const out=document.getElementById("out"), input=document.getElementById("q");
          const esc=s=>s.replace(/[&<>]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;"}[c]));
          async function run(){
            const q=input.value.trim(); if(!q) return;
            const started=performance.now();
            const response=await fetch("/search?q="+encodeURIComponent(q)+"&limit=20");
            const data=await response.json();
            if(!data.hits.length){out.innerHTML='<div class="empty">No documents matched.</div>';return;}
            out.innerHTML='<div class="sub" style="margin-bottom:8px">'+data.total+' matches · '+
              data.took_ms.toFixed(2)+' ms</div>'+data.hits.map(h=>
              '<div class="hit"><h2>'+esc(h.title)+'</h2><div class="meta">doc '+h.id+
              ' · score '+h.score.toFixed(3)+'</div><p>'+esc(h.snippet)+'</p></div>').join("");
          }
          document.getElementById("go").onclick=run;
          input.addEventListener("keydown",e=>{if(e.key==="Enter")run();});
        </script></body></html>
        """;

    private static void page(HttpExchange exchange) throws IOException {
        byte[] bytes = PAGE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void search(HttpExchange exchange) throws IOException {
        Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
        String q = params.getOrDefault("q", "");
        int limit = Math.min(1000, Integer.parseInt(params.getOrDefault("limit", "10")));
        long started = System.nanoTime();
        Engine.Query query = Engine.parse(q);
        List<Engine.Hit> all = engine.search(query, Integer.MAX_VALUE);
        int total = all.size();
        List<Engine.Hit> hits = all.size() > limit ? all.subList(0, limit) : all;
        double ms = (System.nanoTime() - started) / 1e6;

        StringBuilder json = new StringBuilder();
        json.append("{\"query\":").append(quote(q))
            .append(",\"total\":").append(total)
            .append(",\"took_ms\":").append(String.format(Locale.ROOT, "%.3f", ms))
            .append(",\"hits\":[");
        for (int index = 0; index < hits.size(); index++) {
            Engine.Hit hit = hits.get(index);
            if (index > 0) json.append(',');
            json.append("{\"id\":").append(hit.docId())
                .append(",\"title\":").append(quote(engine.title(hit.docId())))
                .append(",\"score\":").append(String.format(Locale.ROOT, "%.4f", hit.score()))
                .append(",\"snippet\":").append(quote(engine.snippetFor(hit.docId(), query)))
                .append('}');
        }
        json.append("]}");
        send(exchange, 200, json.toString());
    }

    private static void stats(HttpExchange exchange) throws IOException {
        String json = String.format(Locale.ROOT,
            "{\"documents\":%d,\"terms\":%d,\"postings_bytes\":%d,\"avg_length\":%.1f}",
            engine.documentCount(), engine.termCount(), engine.postingsBytes(), engine.averageLength());
        send(exchange, 200, json);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) return values;
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            values.put(pair.substring(0, equals), URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
