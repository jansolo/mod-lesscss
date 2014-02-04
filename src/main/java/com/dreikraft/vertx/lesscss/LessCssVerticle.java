package com.dreikraft.vertx.lesscss;

import org.lesscss.LessCompiler;
import org.lesscss.LessException;
import org.lesscss.LessSource;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.*;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * This verticle compiles Less CSS files to CSS on startup or on demand. The Less source file may include other Less
 * source files.
 */
public class LessCssVerticle extends BusModBase {

    public static final String ADDRESS_BASE = LessCssVerticle.class.getName();
    public static final String ADDRESS_COMPILE = ADDRESS_BASE + "/compile";
    public static final int ERR_CODE_BASE = 500;
    public static final String LESS_SRC_FILE = "lessSrcFile";
    public static final String CSS_OUT_FILE = "cssOutFile";
    public static final String CONFIG_COMPILE_ON_START = "compileOnStart";
    public static final String CONFIG_COMPRESS_CSS = "compressCss";
    public static final String LESS_SRC_FILE_DEFAULT = "less/main.less";
    public static final String CSS_OUT_FILE_DEFAULT = "css/main.css";
    private static final String ERR_MSG_INVALID_COMPILE_MESSAGE = "invalid message at %1$s: %2$s";
    private static final String ERR_MSG_COMPILE_FAILED = "failed to compile %1$s: %2$s";
    private static final String ERR_MSG_CSS_WRITE_FAILED = "failed to write %1$s: %2$s";
    private static final String ERR_MSG_UNEXPECTED = "unexpected exception %1$s while processing message %2$s";
    private static final String REPLY_MESSAGE = "message";
    private boolean compressCss;

    @Override
    public void start(final Future<Void> startedResult) {

        // initialize the busmod
        super.start();

        // initialize logger
        logger.info(String.format("starting %1$s ...", this.getClass().getSimpleName()));

        // initialize members
        compressCss = getOptionalBooleanConfig(CONFIG_COMPRESS_CSS, true);

        // compile on message
        logger.info(String.format("registering handler %1$s", ADDRESS_COMPILE));
        eb.registerHandler(ADDRESS_COMPILE, new CompileMessageHandler(), new AsyncResultHandler<Void>() {
            @Override
            public void handle(AsyncResult<Void> registerResult) {
                if (registerResult.succeeded()) {
                    // compile on start
                    if (getOptionalBooleanConfig(CONFIG_COMPILE_ON_START, true)) {
                        final JsonObject compileMsgBody = new JsonObject();
                        compileMsgBody.putString(LESS_SRC_FILE,
                                getOptionalStringConfig(LESS_SRC_FILE, LESS_SRC_FILE_DEFAULT));
                        compileMsgBody.putString(CSS_OUT_FILE,
                                getOptionalStringConfig(CSS_OUT_FILE, CSS_OUT_FILE_DEFAULT));
                        eb.send(ADDRESS_COMPILE, compileMsgBody, new Handler<Message<JsonObject>>() {
                            @Override
                            public void handle(Message<JsonObject> compileResult) {
                                final JsonObject resultBody = compileResult.body();
                                if ("ok".equals(resultBody.getField("status"))) {
                                    startedResult.setResult(null);
                                } else {
                                    startedResult.setFailure(new VertxException(resultBody.getString("message")));
                                }
                            }
                        });
                    } else {
                        startedResult.setResult(null);
                    }
                } else {
                    startedResult.setFailure(registerResult.cause());
                }
            }
        });
    }

    private String extractDir(String target) {
        final String[] targetDirParts = target.split("/");
        final StringBuilder dirBuilder = new StringBuilder();
        if (targetDirParts.length > 1) {
            for (int i = 0; i < targetDirParts.length - 1; i++) {
                dirBuilder.append(targetDirParts[i]).append(i < targetDirParts.length - 2 ? "/" : "");
            }
        }
        return dirBuilder.toString();
    }

    private class CompileMessageHandler implements Handler<Message<JsonObject>> {
        @Override
        public void handle(final Message<JsonObject> compileMessage) {
            final JsonObject msgBody = compileMessage.body();
            try {
                if (logger.isDebugEnabled())
                    logger.debug(String.format("address %1$s received message: %2$s", compileMessage.address(),
                            msgBody != null ? msgBody.encodePrettily() : null));
                final String src = msgBody.getString(LESS_SRC_FILE);
                final String target = msgBody.getString(CSS_OUT_FILE);
                if (src == null || target == null) {
                    compileMessage.fail(ERR_CODE_BASE,
                            String.format(ERR_MSG_INVALID_COMPILE_MESSAGE, compileMessage.address(), msgBody));
                } else {
                    final LessCompiler lessCompiler = new LessCompiler();
                    lessCompiler.setCompress(compressCss);
                    logger.info(String.format("compiling lesscss source: %1$s", src));
                    final URL srcUrl = Thread.currentThread().getContextClassLoader().getResource(src);
                    if (logger.isDebugEnabled())
                        logger.debug(String.format("lesscss source file url: %1$s", srcUrl.toExternalForm()));
                    try {
                        final String css = lessCompiler.compile(new LessSource(new File(srcUrl.toURI())));
                        final String dir = extractDir(target);
                        vertx.fileSystem().mkdirSync(dir, true);
                        vertx.fileSystem().writeFile(target, new Buffer(css, "UTF-8"),
                                new WriteCssResultHandler(compileMessage, target));
                    } catch (LessException | IOException | URISyntaxException e) {
                        compileMessage.fail(ERR_CODE_BASE, String.format(ERR_MSG_COMPILE_FAILED, src,
                                e.getMessage()));
                    }

                }
            } catch (RuntimeException ex) {
                final String msg = String.format(ERR_MSG_UNEXPECTED, ex.getMessage(), msgBody);
                logger.error(msg, ex);
                compileMessage.fail(ERR_CODE_BASE, msg);
            }
        }

        private class WriteCssResultHandler implements Handler<AsyncResult<Void>> {
            private final Message<JsonObject> compileMessage;
            private final String target;

            public WriteCssResultHandler(Message<JsonObject> compileMessage, String target) {
                this.compileMessage = compileMessage;
                this.target = target;
            }

            @Override
            public void handle(AsyncResult<Void> writeCss) {
                try {
                    if (writeCss.succeeded()) {
                        new JsonObject().putString(REPLY_MESSAGE, String.format("successfully wrote compiled" +
                                " css: %1$s",                                target));
                        sendOK(compileMessage, new JsonObject().putString(REPLY_MESSAGE,
                                String.format("successfully wrote compiled css: %1$s", target)));
                    } else {
                        compileMessage.fail(ERR_CODE_BASE,
                                String.format(ERR_MSG_CSS_WRITE_FAILED, target, writeCss.cause()));
                    }
                } catch (RuntimeException ex) {
                    final String msg = String.format(ERR_MSG_UNEXPECTED, ex.getMessage(), compileMessage.body());
                    logger.error(msg, ex);
                    compileMessage.fail(ERR_CODE_BASE, msg);
                }
            }
        }
    }
}
