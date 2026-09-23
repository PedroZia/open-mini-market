package com.minimarket.shared.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(JsonLoggingTest.JsonFileProfile.class)
class JsonLoggingTest {

  @Test
  @DisplayName("o log JSON carrega o traceId do MDC")
  void jsonLogCarriesTraceId() throws IOException, InterruptedException {
    String requestId = UUID.randomUUID().toString();

    given()
        .header(RequestIdFilter.REQUEST_ID_HEADER, requestId)
        .when()
        .get("/test/errors/not-found")
        .then()
        .statusCode(404);

    assertThat(awaitLogWith(requestId)).contains("\"traceId\":\"" + requestId + "\"");
  }

  /**
   * Espelha o {@code %prod} (console em JSON) no handler de arquivo, para poder inspecionar o log
   * no teste; o formato {@code default} inclui o MDC no campo {@code mdc}.
   */
  public static class JsonFileProfile implements QuarkusTestProfile {

    static final Path LOG_PATH = Path.of("target/test-logs/json.log");

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.log.file.enabled", "true",
          "quarkus.log.file.path", LOG_PATH.toString(),
          "quarkus.log.file.json.enabled", "true",
          "quarkus.log.file.async.enabled", "false");
    }
  }

  private static String awaitLogWith(String marker) throws IOException, InterruptedException {
    for (int attempt = 0; attempt < 40; attempt++) {
      if (Files.exists(JsonFileProfile.LOG_PATH)) {
        String log = Files.readString(JsonFileProfile.LOG_PATH);
        if (log.contains(marker)) {
          return log;
        }
      }
      Thread.sleep(50);
    }
    return fail(
        "o log JSON não registrou a requisição "
            + marker
            + " em "
            + JsonFileProfile.LOG_PATH.toAbsolutePath());
  }
}
