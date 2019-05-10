/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2019 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.cutlass.http.processors;

import com.questdb.cairo.PartitionBy;
import com.questdb.cairo.sql.CairoEngine;
import com.questdb.cairo.sql.RecordMetadata;
import com.questdb.cutlass.http.*;
import com.questdb.cutlass.json.JsonException;
import com.questdb.cutlass.text.Atomicity;
import com.questdb.cutlass.text.TextLoader;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.network.DisconnectReason;
import com.questdb.network.IODispatcher;
import com.questdb.network.IOOperation;
import com.questdb.std.*;
import com.questdb.std.str.CharSink;

import java.io.Closeable;


public class TextImportProcessor implements HttpRequestProcessor, HttpMultipartContentListener, Closeable {
    static final int RESPONSE_PREFIX = 1;
    static final int MESSAGE_UNKNOWN = 3;
    private final static Log LOG = LogFactory.getLog(TextImportProcessor.class);
    private static final int RESPONSE_COLUMN = 2;
    private static final int RESPONSE_SUFFIX = 3;
    private static final int MESSAGE_SCHEMA = 1;
    private static final int MESSAGE_DATA = 2;
    private static final int TO_STRING_COL1_PAD = 15;
    private static final int TO_STRING_COL2_PAD = 50;
    private static final int TO_STRING_COL3_PAD = 15;
    private static final int TO_STRING_COL4_PAD = 7;
    private static final int TO_STRING_COL5_PAD = 10;
    private static final CharSequence CONTENT_TYPE_TEXT = "text/plain; charset=utf-8";
    private static final CharSequence CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final CharSequenceIntHashMap atomicityParamMap = new CharSequenceIntHashMap();
    private final LocalValue<TextImportProcessorState> lvContext = new LocalValue<>();
    private final TextImportProcessorConfiguration configuration;
    private final CairoEngine engine;
    private HttpConnectionContext transientContext;
    private IODispatcher<HttpConnectionContext> transientDispatcher;
    private TextImportProcessorState transientState;

    public TextImportProcessor(
            TextImportProcessorConfiguration configuration,
            CairoEngine cairoEngine
    ) {
        this.configuration = configuration;
        this.engine = cairoEngine;
    }

    @Override
    public void close() {

    }

    @Override
    public void onChunk(HttpRequestHeader partHeader, long lo, long hi) {
        if (hi > lo) {
            try {
                transientState.textLoader.parse(lo, (int) (hi - lo));
                if (transientState.messagePart == MESSAGE_DATA && !transientState.analysed) {
                    transientState.analysed = true;
                    transientState.textLoader.setState(TextLoader.LOAD_DATA);
                }
            } catch (JsonException e) {
                // todo: reply something sensible
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onPartBegin(HttpRequestHeader partHeader) throws PeerDisconnectedException, PeerIsSlowToReadException {
        LOG.debug().$("part begin [name=").$(partHeader.getContentDispositionName()).$(']').$();
        if (Chars.equals("data", partHeader.getContentDispositionName())) {

            final HttpRequestHeader rh = transientContext.getRequestHeader();
            CharSequence name = rh.getUrlParam("name");
            if (name == null) {
                name = partHeader.getContentDispositionFilename();
            }
            if (name == null) {
                transientContext.simpleResponse().sendStatus(400, "no name given");
                // we have to disconnect to interrupt potentially large upload
                transientDispatcher.disconnect(transientContext, DisconnectReason.SILLY);
                return;
            }

            transientState.analysed = false;
            transientState.textLoader.configureDestination(
                    name,
                    Chars.equalsNc("true", rh.getUrlParam("overwrite")),
                    Chars.equalsNc("true", rh.getUrlParam("durable")),
                    // todo: these values are incorrect, but ok for now
                    getAtomicity(rh.getUrlParam("atomicity"))
            );
            transientState.textLoader.setForceHeaders(Chars.equalsNc("true", rh.getUrlParam("forceHeader")));
            transientState.textLoader.setState(TextLoader.ANALYZE_STRUCTURE);

            transientState.forceHeader = Chars.equalsNc("true", rh.getUrlParam("forceHeader"));
            transientState.messagePart = MESSAGE_DATA;
        } else if (Chars.equals("schema", partHeader.getContentDispositionName())) {
            transientState.textLoader.setState(TextLoader.LOAD_JSON_METADATA);
            transientState.messagePart = MESSAGE_SCHEMA;
        } else {
            // todo: disconnect
            transientState.messagePart = MESSAGE_UNKNOWN;
        }
    }

    // This processor implements HttpMultipartContentListener, methods of which
    // have neither context nor dispatcher. During "chunk" processing we may need
    // to send something back to client, or disconnect them. To do that we need
    // these transient references. resumeRecv() will set them and they will remain
    // valid during multipart events.

    @Override
    public void onPartEnd(HttpRequestHeader partHeader) throws PeerDisconnectedException, PeerIsSlowToReadException {
        try {
            LOG.debug().$("part end").$();
            transientState.textLoader.wrapUp();
            if (transientState.messagePart == MESSAGE_DATA) {
                sendResponse(transientContext);
            }
        } catch (JsonException e) {
            handleJsonException(e);
        }
    }

    @Override
    public void onHeadersReady(HttpConnectionContext context) {

    }

    @Override
    public void onRequestComplete(HttpConnectionContext context, IODispatcher<HttpConnectionContext> dispatcher) {
        transientState.clear();
        context.clear();
        dispatcher.registerChannel(context, IOOperation.READ);
    }

    @Override
    public void resumeRecv(HttpConnectionContext context, IODispatcher<HttpConnectionContext> dispatcher) {

        this.transientContext = context;
        this.transientDispatcher = dispatcher;
        this.transientState = lvContext.get(context);
        if (this.transientState == null) {
            try {
                lvContext.set(context, this.transientState = new TextImportProcessorState(configuration.getTextConfiguration(), engine));
            } catch (JsonException e) {
                // todo: handle gracefully
                e.printStackTrace();
            }
        }
    }

    @Override
    public void resumeSend(HttpConnectionContext context, IODispatcher<HttpConnectionContext> dispatcher) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doResumeSend(lvContext.get(context), context.getChunkedResponseSocket());
    }

    private static void resumeJson(TextImportProcessorState state, HttpResponseSink.ChunkedResponseImpl r) throws PeerDisconnectedException, PeerIsSlowToReadException {
        final TextLoader textLoader = state.textLoader;
        final RecordMetadata m = textLoader.getMetadata();
        final int columnCount = m.getColumnCount();
        final LongList errors = textLoader.getColumnErrorCounts();


        switch (state.responseState) {
            case RESPONSE_PREFIX:
                long totalRows = state.textLoader.getParsedLineCount();
                long importedRows = state.textLoader.getWrittenLineCount();
                r.put('{')
                        .putQuoted("status").put(':').putQuoted("OK").put(',')
                        .putQuoted("location").put(':').encodeUtf8AndQuote(textLoader.getTableName()).put(',')
                        .putQuoted("rowsRejected").put(':').put(totalRows - importedRows).put(',')
                        .putQuoted("rowsImported").put(':').put(importedRows).put(',')
                        .putQuoted("header").put(':').put(textLoader.isForceHeaders()).put(',')
                        .putQuoted("columns").put(':').put('[');
                state.responseState = RESPONSE_COLUMN;
                // fall through
            case RESPONSE_COLUMN:
                for (; state.columnIndex < columnCount; state.columnIndex++) {
                    r.bookmark();
                    if (state.columnIndex > 0) {
                        r.put(',');
                    }
                    r.put('{').
                            putQuoted("name").put(':').putQuoted(m.getColumnName(state.columnIndex)).put(',').
                            putQuoted("type").put(':').putQuoted(com.questdb.cairo.ColumnType.nameOf(m.getColumnType(state.columnIndex))).put(',').
                            putQuoted("size").put(':').put(com.questdb.cairo.ColumnType.sizeOf(m.getColumnType(state.columnIndex))).put(',').
                            putQuoted("errors").put(':').put(errors.getQuick(state.columnIndex));

                    // todo: resolve these attributes
//                    if (im.pattern != null) {
//                        r.put(',').putQuoted("pattern").put(':').putQuoted(im.pattern);
//                    }
//
//                    if (im.dateLocale != null) {
//                        r.put(',').putQuoted("locale").put(':').putQuoted(im.dateLocale.getId());
//                    }

                    r.put('}');
                }
                state.responseState = RESPONSE_SUFFIX;
                // fall through
            case RESPONSE_SUFFIX:
                r.bookmark();
                r.put(']').put('}');
                r.sendChunk();
                r.done();
                break;
            default:
                break;
        }
    }

    private static CharSink pad(CharSink b, int w, CharSequence value) {
        int pad = value == null ? w : w - value.length();
        replicate(b, ' ', pad);

        if (value != null) {
            if (pad < 0) {
                b.put("...").put(value.subSequence(-pad + 3, value.length()));
            } else {
                b.put(value);
            }
        }

        b.put("  |");

        return b;
    }

    private static void pad(CharSink b, int w, long value) {
        int len = (int) Math.log10(value);
        if (len < 0) {
            len = 0;
        }
        replicate(b, ' ', w - len - 1);
        b.put(value);
        b.put("  |");
    }

    private static void replicate(CharSink b, char c, int times) {
        for (int i = 0; i < times; i++) {
            b.put(c);
        }
    }

    private static void sep(CharSink b) {
        b.put('+');
        replicate(b, '-', TO_STRING_COL1_PAD + TO_STRING_COL2_PAD + TO_STRING_COL3_PAD + TO_STRING_COL4_PAD + TO_STRING_COL5_PAD + 14);
        b.put("+\r\n");
    }

    private static void resumeText(TextImportProcessorState h, HttpResponseSink.ChunkedResponseImpl r) throws PeerDisconnectedException, PeerIsSlowToReadException {
        final TextLoader textLoader = h.textLoader;
        final RecordMetadata metadata = textLoader.getMetadata();
        LongList errors = textLoader.getColumnErrorCounts();


        switch (h.responseState) {
            case RESPONSE_PREFIX:
                sep(r);
                r.put('|');
                pad(r, TO_STRING_COL1_PAD, "Location:");
                pad(r, TO_STRING_COL2_PAD, textLoader.getTableName());
                pad(r, TO_STRING_COL3_PAD, "Pattern");
                pad(r, TO_STRING_COL4_PAD, "Locale");
                pad(r, TO_STRING_COL5_PAD, "Errors").put(Misc.EOL);


                r.put('|');
                pad(r, TO_STRING_COL1_PAD, "Partition by");
                pad(r, TO_STRING_COL2_PAD, PartitionBy.toString(textLoader.getPartitionBy()));
                pad(r, TO_STRING_COL3_PAD, "");
                pad(r, TO_STRING_COL4_PAD, "");
                pad(r, TO_STRING_COL5_PAD, "").put(Misc.EOL);
                sep(r);

                r.put('|');
                pad(r, TO_STRING_COL1_PAD, "Rows handled");
                pad(r, TO_STRING_COL2_PAD, textLoader.getParsedLineCount());
                pad(r, TO_STRING_COL3_PAD, "");
                pad(r, TO_STRING_COL4_PAD, "");
                pad(r, TO_STRING_COL5_PAD, "").put(Misc.EOL);

                r.put('|');
                pad(r, TO_STRING_COL1_PAD, "Rows imported");
                pad(r, TO_STRING_COL2_PAD, textLoader.getWrittenLineCount());
                pad(r, TO_STRING_COL3_PAD, "");
                pad(r, TO_STRING_COL4_PAD, "");
                pad(r, TO_STRING_COL5_PAD, "").put(Misc.EOL);
                sep(r);

                h.responseState = RESPONSE_COLUMN;
                // fall through
            case RESPONSE_COLUMN:

                final int columnCount = metadata.getColumnCount();

                for (; h.columnIndex < columnCount; h.columnIndex++) {
                    r.bookmark();
                    r.put('|');
                    pad(r, TO_STRING_COL1_PAD, h.columnIndex);
//                    col(r, m.getColumnQuick(h.columnIndex), importedMetadata.getQuick(h.columnIndex));
                    pad(r, TO_STRING_COL5_PAD, errors.getQuick(h.columnIndex));
                    r.put(Misc.EOL);
                }
                h.responseState = RESPONSE_SUFFIX;
                // fall through
            case RESPONSE_SUFFIX:
                r.bookmark();
                sep(r);
                r.sendChunk();
                r.done();
                break;
            default:
                break;
        }
    }

    private static int getAtomicity(CharSequence name) {
        if (name == null) {
            return Atomicity.SKIP_COL;
        }

        int atomicity = atomicityParamMap.get(name);
        return atomicity == -1 ? Atomicity.SKIP_COL : atomicity;
    }

    private void doResumeSend(TextImportProcessorState state, HttpResponseSink.ChunkedResponseImpl response) throws PeerDisconnectedException, PeerIsSlowToReadException {
        try {

            if (state.json) {
                resumeJson(state, response);
            } else {
                resumeText(state, response);
            }
        } catch (NoSpaceLeftInResponseBufferException ignored) {
            if (response.resetToBookmark()) {
                response.sendChunk();
            } else {
                // what we have here is out unit of data, column value or query
                // is larger that response content buffer
                // all we can do in this scenario is to log appropriately
                // and disconnect socket
                // todo: this is a force disconnect
                throw PeerDisconnectedException.INSTANCE;
            }
        }

        state.clear();
    }

    private void handleJsonException(JsonException e) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (configuration.abortBrokenUploads()) {
            sendError(transientContext, e.getMessage(), transientState.json);
            throw PeerDisconnectedException.INSTANCE;
        }
        transientState.state = TextImportProcessorState.STATE_DATA_ERROR;
        transientState.stateMessage = e.getMessage();
    }

    private void sendError(HttpConnectionContext context, String message, boolean json) throws PeerDisconnectedException, PeerIsSlowToReadException {
        HttpResponseSink.ChunkedResponseImpl sink = context.getChunkedResponseSocket();
        if (json) {
            sink.status(200, CONTENT_TYPE_JSON);
            sink.put('{').putQuoted("status").put(':').encodeUtf8AndQuote(message).put('}');
        } else {
            sink.status(400, CONTENT_TYPE_TEXT);
            sink.encodeUtf8(message);
        }
        // todo: is this needed, both of these?
        sink.done();
        throw PeerDisconnectedException.INSTANCE;
    }

    private void sendResponse(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
        TextImportProcessorState state = lvContext.get(context);
        // todo: may be set this up when headers are ready?
        state.json = Chars.equalsNc("json", context.getRequestHeader().getUrlParam("fmt"));
        HttpResponseSink.ChunkedResponseImpl response = context.getChunkedResponseSocket();

        if (state.state == TextImportProcessorState.STATE_OK) {
            if (state.json) {
                response.status(200, CONTENT_TYPE_JSON);
            } else {
                response.status(200, CONTENT_TYPE_TEXT);
            }
            response.sendHeader();
            doResumeSend(state, response);
        } else {
            sendError(context, state.stateMessage, state.json);
        }
    }

    static {
        atomicityParamMap.put("relaxed", Atomicity.SKIP_ROW);
        atomicityParamMap.put("strict", Atomicity.SKIP_ALL);
    }
}
