package com.dreikraft.vertx.lesscss;

import com.dreikraft.vertx.AsyncResultBase;
import com.dreikraft.vertx.json.JsonResult;
import org.lesscss.LessCompiler;
import org.lesscss.LessException;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Future;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.file.FileProps;
import org.vertx.java.core.file.FileSystemException;
import org.vertx.java.core.json.JsonObject;

/**
 * Created by jan_solo on 09.01.14.
 */
public class LessCssVerticle extends BusModBase {

    public static final String LESSCSS_COMPILE_MESSAGE = "lesscss.compile";
    public static final String LESS_SRC_FILE = "lessSrcFile";
    public static final String LESS_SRC_FILE_DEFAULT = "less/main.less";
    public static final String CSS_OUT_FILE = "cssOutFile";
    public static final String CSS_OUT_FILE_DEFAULT = "css/main.css";

    @Override
    public void start(final Future<Void> startedResult) {

        super.start();

        // compile on start
        compileAndWrite(getOptionalStringConfig(LESS_SRC_FILE, LESS_SRC_FILE_DEFAULT),
                getOptionalStringConfig(CSS_OUT_FILE, CSS_OUT_FILE_DEFAULT), new Handler<AsyncResult<Void>>() {
            @Override
            public void handle(AsyncResult<Void> compileResult) {
                if (compileResult.failed()) {
                    startedResult.setFailure(compileResult.cause());
                } else {
                    startedResult.setResult(null);
                }
            }
        });

        // compile on message
        vertx.eventBus().registerHandler(LESSCSS_COMPILE_MESSAGE, new Handler<Message<JsonObject>>() {
            @Override
            public void handle(final Message<JsonObject> compileMessage) {
                final JsonObject msgBody = compileMessage.body();
                logger.info("received message: lesscss.compile " + msgBody.encodePrettily());
                final String src = msgBody.getString(LESS_SRC_FILE);
                final String out = msgBody.getString(CSS_OUT_FILE);
                if (src == null && out == null) {
                    compileMessage.reply(new JsonResult(JsonResult.Status.FAILED,
                            "invalid message body: lesscss.compile" + msgBody.encode()));
                } else {
                    compileAndWrite(src, out, new Handler<AsyncResult<Void>>() {
                        @Override
                        public void handle(AsyncResult<Void> compileResult) {
                            if (compileResult.failed()) {
                                logger.error("lesscss.compile failed: " + msgBody.encode(),
                                        compileResult.cause());
                                compileMessage.reply(new JsonResult(JsonResult.Status.FAILED,
                                        "lesscss.compile failed: " + compileResult.cause().getMessage()));
                            } else {
                                compileMessage.reply(new JsonResult(JsonResult.Status.SUCCESS,
                                        "lesscss.compile succeeded: " + msgBody.encode()));
                            }
                        }
                    });
                }
            }

        });
    }

    private void compileAndWrite(final String src, final String target, final Handler<AsyncResult<Void>> resultHandler) {
        compile(src, new Handler<AsyncResult<String>>() {
            @Override
            public void handle(AsyncResult<String> compileResult) {
                if (compileResult.failed()) {
                    resultHandler.handle(new AsyncResultBase<Void>(compileResult.cause()));
                } else {
                    writeCss(target, compileResult.result(), new Handler<AsyncResult<Void>>() {
                        @Override
                        public void handle(AsyncResult<Void> writeCssResult) {
                            if (writeCssResult.failed()) {
                                resultHandler.handle(new AsyncResultBase<Void>(writeCssResult.cause()));
                            } else {
                                resultHandler.handle(new AsyncResultBase<Void>((Void) null));
                            }
                        }
                    });
                }
            }
        });
    }

    private void compile(final String src, final Handler<AsyncResult<String>> resultHandler) {
        final LessCompiler lessCompiler = new LessCompiler();
        lessCompiler.setCompress(getOptionalBooleanConfig("compress", true));
        vertx.fileSystem().readFile(src, new Handler<AsyncResult<Buffer>>() {
            @Override
            public void handle(AsyncResult<Buffer> readFileResult) {
                if (readFileResult.failed()) {
                    resultHandler.handle(new AsyncResultBase<String>(readFileResult.cause()));
                } else {
                    try {
                        resultHandler.handle(new AsyncResultBase<String>(
                                lessCompiler.compile(readFileResult.result().toString("UTF-8"))));
                    } catch (LessException e) {
                        resultHandler.handle(new AsyncResultBase<String>(e));
                    }
                }
            }
        });
    }

    private void writeCss(final String target, final String css, final Handler<AsyncResult<Void>> resultHandler) {
        createCssDir(target, new AsyncResultHandler<Void>() {
            @Override
            public void handle(AsyncResult<Void> createCssDirResult) {
                if (createCssDirResult.failed()) {
                    resultHandler.handle(new AsyncResultBase<Void>(createCssDirResult.cause()));
                } else {
                    vertx.fileSystem().writeFile(target, new Buffer(css, "UTF-8"), new Handler<AsyncResult<Void>>() {
                        @Override
                        public void handle(AsyncResult<Void> writeFileResult) {
                            if (writeFileResult.failed()) {
                                resultHandler.handle(new AsyncResultBase<Void>(writeFileResult.cause()));
                            } else {
                                resultHandler.handle(new AsyncResultBase<Void>((Void) null));
                            }
                        }
                    });
                }
            }
        });
    }

    private void createCssDir(final String target, final AsyncResultHandler<Void> resultHandler) {
        final String dir = extractDir(target);
        vertx.fileSystem().props(dir, new Handler<AsyncResult<FileProps>>() {
            @Override
            public void handle(AsyncResult<FileProps> propsResult) {
                if (propsResult.failed()) {
                    vertx.fileSystem().mkdir(dir, true, new AsyncResultHandler<Void>() {
                        @Override
                        public void handle(AsyncResult<Void> mkdirResult) {
                            if (mkdirResult.failed()) {
                                resultHandler.handle(new AsyncResultBase<Void>(mkdirResult.cause()));
                            } else {
                                resultHandler.handle(new AsyncResultBase<Void>((Void)null));
                            }
                        }
                    });
                } else {
                    if (!propsResult.result().isDirectory()) {
                        resultHandler.handle(new AsyncResultBase<Void>(
                                new FileSystemException("file already exists and is not a directory: " + dir)));
                    } else {
                        resultHandler.handle(new AsyncResultBase<Void>((Void)null));
                    }
                }
            }
        });
    }

    private String extractDir(String target) {
        final String[] targetDirParts = target.split("/");
        final StringBuilder dirBuilder = new StringBuilder();
        if (targetDirParts.length > 1) {
            for (int i=0; i < targetDirParts.length - 1; i++) {
                dirBuilder.append(targetDirParts[i]).append(i < targetDirParts.length - 2 ? "/" : "");
            }
        }
        return dirBuilder.toString();
    }

}
