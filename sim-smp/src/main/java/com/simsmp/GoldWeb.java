package com.simsmp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Tiny read-only web server for the gold price page (kept in its own class so a Java without jdk.httpserver only loses the page). */
final class GoldWeb {
   private GoldWeb() {
   }

   /** returns a Runnable that stops the server */
   static Runnable start(int port, Supplier<String> json) throws IOException {
      HttpServer srv = HttpServer.create(new InetSocketAddress(port), 0);
      srv.createContext("/api/gold", ex -> {
         byte[] body = json.get().getBytes(StandardCharsets.UTF_8);
         ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
         ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
         ex.getResponseHeaders().set("Cache-Control", "no-store");
         ex.sendResponseHeaders(200, body.length);
         try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
         }
      });
      srv.createContext("/api/economy", ex -> {
         PriceEngine pe = PriceEngine.get();
         byte[] body = (pe == null ? "{}" : pe.json()).getBytes(StandardCharsets.UTF_8);
         ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
         ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
         ex.getResponseHeaders().set("Cache-Control", "no-store");
         ex.sendResponseHeaders(200, body.length);
         try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
         }
      });
      srv.createContext("/economy", ex -> {
         byte[] body = EconomyPage.HTML.getBytes(StandardCharsets.UTF_8);
         ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
         ex.sendResponseHeaders(200, body.length);
         try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
         }
      });
      srv.createContext("/", ex -> {
         String path = ex.getRequestURI().getPath();
         if (!path.equals("/") && !path.equals("/index.html")) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
         }

         byte[] body = GoldPage.HTML.getBytes(StandardCharsets.UTF_8);
         ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
         ex.sendResponseHeaders(200, body.length);
         try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
         }
      });
      srv.setExecutor(Executors.newFixedThreadPool(2, r -> {
         Thread t = new Thread(r, "sim-bank-web");
         t.setDaemon(true);
         return t;
      }));
      srv.start();
      return () -> srv.stop(0);
   }
}
