package com.dreikraft.vertx.lesscss;

import org.junit.Test;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;
import org.vertx.testtools.TestVerticle;
import org.vertx.testtools.VertxAssert;

/**
 * Created by jan_solo on 09.01.14.
 */
public class LessCssVerticleTest extends TestVerticle {

    @Test
    public void testStart() {

        final JsonObject config = new JsonObject();
        config.putString(LessCssVerticle.CSS_OUT_FILE, "/tmp/css/main.css");
        container.deployVerticle(LessCssVerticle.class.getName(), config, new Handler<AsyncResult<String>>() {
            @Override
            public void handle(AsyncResult<String> event) {
                if (event.failed()) {
                    container.logger().error(event.cause().getMessage(), event.cause());
                    VertxAssert.fail(event.cause().getMessage());
                } else {
                    container.logger().info(event.result());
                    VertxAssert.testComplete();
                }
            }
        });

    }
}
