package com.dreikraft.vertx.lesscss;

import org.junit.Test;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.testtools.TestVerticle;
import org.vertx.testtools.VertxAssert;

/**
 * Tests the less css verticle.
 *
 * @author jan_solo
 */
public class LessCssVerticleTest extends TestVerticle {

    @Override
    public void start() {
        initialize();
        final JsonObject config = new JsonObject();
        config.putBoolean(LessCssVerticle.CONFIG_COMPILE_ON_START, false);
        container.deployModule(System.getProperty("vertx.modulename"), config, new AsyncResultHandler<String>() {
            @Override
            public void handle(AsyncResult<String> startResult) {
                if (startResult.failed()) {
                    container.logger().error(startResult.cause().getMessage(), startResult.cause());
                }
                VertxAssert.assertTrue(startResult.succeeded());
                VertxAssert.assertNotNull("deploymentID should not be null", startResult.result());
                startTests();
            }
        });
    }

    @Test
    public void testCompile() {
        final JsonObject msg = new JsonObject();
        msg.putString(LessCssVerticle.LESS_SRC_FILE, LessCssVerticle.LESS_SRC_FILE_DEFAULT);
        msg.putString(LessCssVerticle.CSS_OUT_FILE, LessCssVerticle.CSS_OUT_FILE_DEFAULT);

        vertx.eventBus().send(LessCssVerticle.ADDRESS_COMPILE, msg, new Handler<Message<JsonObject>>() {
            @Override
            public void handle(final Message<JsonObject> result) {
                try {
                    VertxAssert.assertEquals("ok", result.body().getString("status"));
                    VertxAssert.assertTrue(vertx.fileSystem().existsSync(LessCssVerticle.CSS_OUT_FILE_DEFAULT));
                    vertx.fileSystem().deleteSync(LessCssVerticle.CSS_OUT_FILE_DEFAULT);
                } catch (RuntimeException ex) {
                    VertxAssert.fail(ex.getMessage());
                }
                VertxAssert.testComplete();
            }
        });
    }
}
