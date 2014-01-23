package com.dreikraft.vertx.lesscss;

import org.lesscss.LessCompiler;
import org.lesscss.LessException;
import org.lesscss.LessSource;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Future;
import org.vertx.java.core.Handler;
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
    public static final String CONFIG_REPLY_TIMEOUT = "replyTimeout";
    public static final long REPLY_TIMEOUT = 30 * 1000;
    public static final int ERR_CODE_BASE = 200;
    public static final int ERR_CODE_INVALID_COMPILE_MESSAGE = ERR_CODE_BASE;
    public static final String ERR_MSG_INVALID_COMPILE_MESSAGE = "invalid message at %1$s: %2$s";
    public static final int ERR_CODE_COMPILE_FAILED = ERR_CODE_BASE + 1;
    public static final String ERR_MSG_COMPILE_FAILED = "failed to compile %1$s: %2$s";
    public static final int ERR_CODE_CSS_WRITE_FAILED = ERR_CODE_BASE + 2;
    public static final String ERR_MSG_CSS_WRITE_FAILED = "failed to write %1$s: %2$s";
    public static final String LESS_SRC_FILE = "lessSrcFile";
    public static final String LESS_SRC_FILE_DEFAULT = "less/main.less";
    public static final String CSS_OUT_FILE = "cssOutFile";
    public static final String CSS_OUT_FILE_DEFAULT = "css/main.css";
    public static final String CONFIG_COMPILE_ON_START = "compileOnStart";
    public static final String CONFIG_COMPRESS_CSS = "compressCss";
    private long replyTimeout;
    private boolean compressCss;

    @Override
    public void start(final Future<Void> startedResult) {

        // initilize the busmod
        super.start();

        // initialize logger
        logger.info(String.format("starting %1$s ...", this.getClass().getSimpleName()));

        // initialize members
        replyTimeout = getOptionalLongConfig(CONFIG_REPLY_TIMEOUT, REPLY_TIMEOUT);
        compressCss = getOptionalBooleanConfig(CONFIG_COMPRESS_CSS, true);

        // compile on message
        eb.registerHandler(ADDRESS_COMPILE, new CompileMessageHandler());

        // compile on start
        if (getOptionalBooleanConfig(CONFIG_COMPILE_ON_START, true)) {
            final JsonObject compileMsgBody = new JsonObject();
            compileMsgBody.putString(LESS_SRC_FILE, getOptionalStringConfig(LESS_SRC_FILE, LESS_SRC_FILE_DEFAULT));
            compileMsgBody.putString(CSS_OUT_FILE, getOptionalStringConfig(CSS_OUT_FILE, CSS_OUT_FILE_DEFAULT));
            eb.sendWithTimeout(ADDRESS_COMPILE, compileMsgBody, replyTimeout,
                    new AsyncResultHandler<Message<Void>>() {
                        @Override
                        public void handle(AsyncResult<Message<Void>> compileResult) {
                            if (compileResult.failed()) {
                                startedResult.setFailure(compileResult.cause());
                            } else {
                                startedResult.setResult(null);
                            }
                        }
                    });
        } else {
            startedResult.setResult(null);
        }
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
            if (logger.isDebugEnabled())
                logger.debug(String.format("address %1$s received message: %2$s", compileMessage.address(),
                        msgBody != null ? msgBody.encodePrettily() : null));
            final String src = msgBody.getString(LESS_SRC_FILE);
            final String target = msgBody.getString(CSS_OUT_FILE);
            if (src == null || target == null) {
                compileMessage.fail(ERR_CODE_INVALID_COMPILE_MESSAGE,
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
                    compileMessage.fail(ERR_CODE_COMPILE_FAILED, String.format(ERR_MSG_COMPILE_FAILED, src,
                            e.getMessage()));
                }

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
                if (writeCss.succeeded()) {
                    logger.info(String.format("successfully wrote compile css: %1$s", target));
                    compileMessage.reply();
                } else {
                    compileMessage.fail(ERR_CODE_CSS_WRITE_FAILED,
                            String.format(ERR_MSG_CSS_WRITE_FAILED, target, writeCss.cause()));
                }
            }
        }
    }
}
