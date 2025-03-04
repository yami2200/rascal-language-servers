/*
 * Copyright (c) 2018-2021, NWO-I CWI and Swat.engineering
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.rascalmpl.vscode.lsp.parametric;

import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensDelta;
import org.eclipse.lsp4j.SemanticTokensDeltaParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.rascalmpl.exceptions.Throw;
import org.rascalmpl.parser.gtd.exception.ParseError;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.values.parsetrees.ITree;
import org.rascalmpl.vscode.lsp.BaseWorkspaceService;
import org.rascalmpl.vscode.lsp.IBaseLanguageClient;
import org.rascalmpl.vscode.lsp.IBaseTextDocumentService;
import org.rascalmpl.vscode.lsp.TextDocumentState;
import org.rascalmpl.vscode.lsp.parametric.model.ParametricFileFacts;
import org.rascalmpl.vscode.lsp.parametric.model.ParametricSummaryBridge;
import org.rascalmpl.vscode.lsp.terminal.ITerminalIDEServer.LanguageParameter;
import org.rascalmpl.vscode.lsp.util.Diagnostics;
import org.rascalmpl.vscode.lsp.util.FoldingRanges;
import org.rascalmpl.vscode.lsp.util.Outline;
import org.rascalmpl.vscode.lsp.util.SemanticTokenizer;
import org.rascalmpl.vscode.lsp.util.concurrent.InterruptibleFuture;
import org.rascalmpl.vscode.lsp.util.locations.ColumnMaps;
import org.rascalmpl.vscode.lsp.util.locations.LineColumnOffsetMap;
import org.rascalmpl.vscode.lsp.util.locations.Locations;
import com.google.common.io.CharStreams;
import io.usethesource.vallang.IBool;
import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.ITuple;
import io.usethesource.vallang.IValue;
import io.usethesource.vallang.IWithKeywordParameters;

// suppress required due to forced usage of deprecated `SymbolInformation` class in `Either` until LSP4J cleans it up:
@SuppressWarnings({"deprecation"}) 
public class ParametricTextDocumentService implements IBaseTextDocumentService, LanguageClientAware {
    private static final Logger logger = LogManager.getLogger(ParametricTextDocumentService.class);
    private final ExecutorService ownExecuter;

    private final String dedicatedLanguageName;
    private final SemanticTokenizer tokenizer = new SemanticTokenizer();
    private @MonotonicNonNull LanguageClient client;
    private @MonotonicNonNull BaseWorkspaceService workspaceService;

    private final Map<ISourceLocation, TextDocumentState> files;
    private final ColumnMaps columns;

    private final Map<String, ParametricFileFacts> facts = new ConcurrentHashMap<>();
    private final Map<String, LanguageContributionsMultiplexer> contributions = new ConcurrentHashMap<>();

    private final @Nullable LanguageParameter dedicatedLanguage;

    public ParametricTextDocumentService(ExecutorService exec, @Nullable LanguageParameter dedicatedLanguage) {
        this.ownExecuter = exec;
        this.files = new ConcurrentHashMap<>();
        this.columns = new ColumnMaps(this::getContents);
        if (dedicatedLanguage == null) {
            this.dedicatedLanguageName = "";
            this.dedicatedLanguage = null;
        }
        else {
            this.dedicatedLanguageName = dedicatedLanguage.getName();
            this.dedicatedLanguage = dedicatedLanguage;
        }
    }

    @Override
    public LineColumnOffsetMap getColumnMap(ISourceLocation file) {
        return columns.get(file);
    }

    private String getContents(ISourceLocation file) {
        file = file.top();
        TextDocumentState ideState = files.get(file);
        if (ideState != null) {
            return ideState.getCurrentContent();
        }
        try (Reader src = URIResolverRegistry.getInstance().getCharacterReader(file)) {
            return CharStreams.toString(src);
        }
        catch (IOException e) {
            logger.error("Error opening file {} to get contents", file, e);
            return "";
        }
    }

    public void initializeServerCapabilities(ServerCapabilities result) {
        result.setDefinitionProvider(true);
        result.setTextDocumentSync(TextDocumentSyncKind.Full);
        result.setHoverProvider(true);
        result.setReferencesProvider(true);
        result.setDocumentSymbolProvider(true);
        result.setImplementationProvider(true);
        result.setSemanticTokensProvider(tokenizer.options());
        result.setCodeLensProvider(new CodeLensOptions(false));
        String commandName = BaseWorkspaceService.RASCAL_META_COMMAND;
        if (!dedicatedLanguageName.isEmpty()) {
            commandName += "-" + dedicatedLanguageName;
        }
        result.setExecuteCommandProvider(new ExecuteCommandOptions(Collections.singletonList(commandName)));
        result.setFoldingRangeProvider(true);
        result.setInlayHintProvider(true);
    }

    @Override
    public void pair(BaseWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        facts.values().forEach(v -> v.setClient(client));
        if (dedicatedLanguage != null) {
            // if there was one scheduled, we now start it up, since the connection has been made
            this.registerLanguage(dedicatedLanguage);
        }
    }

    // LSP interface methods

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        logger.debug("Did Open file: {}", params.getTextDocument());
        handleParsingErrors(open(params.getTextDocument()));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        logger.trace("Change contents: {}", params.getTextDocument());
        updateContents(params.getTextDocument(), last(params.getContentChanges()).getText());
        invalidateFacts(params.getTextDocument());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        logger.debug("Did Close: {}", params.getTextDocument());
        if (files.remove(Locations.toLoc(params.getTextDocument())) == null) {
            throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError,
                "Unknown file: " + Locations.toLoc(params.getTextDocument()), params));
        }
        facts(params.getTextDocument()).close(Locations.toLoc(params.getTextDocument()));
    }

    private void triggerSummary(TextDocumentIdentifier doc) {
        facts(doc).calculate(Locations.toLoc(doc));
    }

    private void invalidateFacts(TextDocumentIdentifier doc) {
        facts(doc).invalidate(Locations.toLoc(doc));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        logger.debug("Save: {}", params.getTextDocument());
        // on save we don't get new file contents, that already came in via didChange
        // but we do trigger the type checker on save
        triggerSummary(params.getTextDocument());
    }

    private TextDocumentState updateContents(TextDocumentIdentifier doc, String newContents) {
        TextDocumentState file = getFile(doc);
        logger.trace("New contents for {}", doc);
        handleParsingErrors(file, file.update(newContents));
        return file;
    }

    private void handleParsingErrors(TextDocumentState file, CompletableFuture<ITree> futureTree) {
        futureTree.handle((tree, excp) -> {
            Diagnostic newParseError = null;
            if (excp != null && excp instanceof CompletionException) {
                excp = excp.getCause();
            }

            if (excp instanceof Throw) {
                Throw thrown = (Throw) excp;
                newParseError = Diagnostics.translateRascalParseError(thrown.getException(), columns);
            }
            else if (excp instanceof ParseError) {
                newParseError = Diagnostics.translateDiagnostic((ParseError)excp, columns);
            }
            else if (excp != null) {
                logger.error("Parsing crashed", excp);
                newParseError = new Diagnostic(
                    new Range(new Position(0,0), new Position(0,1)),
                    "Parsing failed: " + excp.getMessage(),
                    DiagnosticSeverity.Error,
                    "Rascal Parser");
            }
            logger.trace("Finished parsing tree, reporting new parse error: {} for: {}", newParseError, file.getLocation());
            facts(file.getLocation()).reportParseErrors(file.getLocation(),
                newParseError == null ? Collections.emptyList() : Collections.singletonList(newParseError));
            return null;
        });
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        logger.trace("codeLens for: {}", params.getTextDocument().getUri());
        final TextDocumentState file = getFile(params.getTextDocument());
        final ILanguageContributions contrib = contributions(params.getTextDocument());

        return recoverExceptions(file.getCurrentTreeAsync()
            .thenApply(contrib::lenses)
            .thenCompose(InterruptibleFuture::get)
            .thenApply(s -> s.stream()
                .map(e -> locCommandTupleToCodeLense(contrib.getExtension(), e))
                .collect(Collectors.toList())
            ), () -> null);
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        logger.trace("inlayHint for: {}", params.getTextDocument().getUri());
        final TextDocumentState file = getFile(params.getTextDocument());
        final ILanguageContributions contrib = contributions(params.getTextDocument());
        return recoverExceptions(
                recoverExceptions(file.getCurrentTreeAsync(), file::getMostRecentTree)
                .thenApply(contrib::inlayHint)
                .thenCompose(InterruptibleFuture::get)
                .thenApply(s -> s.stream()
                    .map(this::rowToInlayHint)
                    .collect(Collectors.toList())
            ), () -> null);
    }


    private static <T> CompletableFuture<T> recoverExceptions(CompletableFuture<T> future, Supplier<T> defaultValue) {
        return future
            .exceptionally(e -> {
                logger.error("Operation failed with", e);
                return defaultValue.get();
            });
    }

    private InlayHint rowToInlayHint(IValue v) {
        // unpack rascal value
        var t = (IConstructor) v;
        var loc =(ISourceLocation) t.get("position");
        var label = ((IString) t.get("label")).getValue();
        var kind = (IConstructor) t.get("kind");
        var toolTip = (IString)t.asWithKeywordParameters().getParameter("toolTip");
        var atEnd = (IBool)t.asWithKeywordParameters().getParameter("atEnd");


        // translate to lsp
        var result = new InlayHint(Locations.toPosition(loc, columns, atEnd.getValue()), Either.forLeft(label.trim()));
        result.setKind(kind.getName().equals("type") ? InlayHintKind.Type : InlayHintKind.Parameter);
        result.setPaddingLeft(label.startsWith(" "));
        result.setPaddingRight(label.endsWith(" "));
        if (toolTip != null && toolTip.length() > 0) {
            result.setTooltip(toolTip.getValue());
        }
        return result;
    }

    private CodeLens locCommandTupleToCodeLense(String extension, IValue v) {
        ITuple t = (ITuple) v;
        ISourceLocation loc = (ISourceLocation) t.get(0);
        IConstructor command = (IConstructor) t.get(1);

        return new CodeLens(Locations.toRange(loc, columns), constructorToCommand(extension, command), null);
    }

    private Command constructorToCommand(String extension, IConstructor command) {
        IWithKeywordParameters<?> kw = command.asWithKeywordParameters();

        return new Command(kw.hasParameter("title") ? ((IString) kw.getParameter("title")).getValue() : command.toString(), BaseWorkspaceService.RASCAL_META_COMMAND, Arrays.asList(extension, command.toString()));
    }

    private void handleParsingErrors(TextDocumentState file) {
        handleParsingErrors(file, file.getCurrentTreeAsync());
    }

    private static <T> T last(List<T> l) {
        return l.get(l.size() - 1);
    }

    private ILanguageContributions contributions(TextDocumentIdentifier doc) {
        return contributions(doc.getUri());
    }

    private ILanguageContributions contributions(TextDocumentItem doc) {
        return contributions(doc.getUri());
    }

    private ILanguageContributions contributions(String doc) {
        ILanguageContributions contrib = contributions.get(extension(doc));

        if (contrib != null) {
            return contrib;
        }

        throw new UnsupportedOperationException("Rascal Parametric LSP has no support for this file: " + doc);
    }

    private static String extension(String file) {
        int index = file.lastIndexOf(".");
        if (index != -1) {
            return file.substring(index + 1);
        }
        return "";
    }

    private ParametricFileFacts facts(TextDocumentIdentifier doc) {
        return facts(doc.getUri());
    }

    private ParametricFileFacts facts(ISourceLocation doc) {
        return facts(doc.getPath());
    }

    private ParametricFileFacts facts(String doc) {
        ParametricFileFacts fact = facts.get(extension(doc));
        if (fact != null) {
            return fact;
        }
        throw new UnsupportedOperationException("Rascal Parametric LSP has no support for this file: " + doc);
    }

    private TextDocumentState open(TextDocumentItem doc) {
        return files.computeIfAbsent(Locations.toLoc(doc),
            l -> new TextDocumentState(contributions(doc)::parseSourceFile, l, doc.getText())
        );
    }

    private TextDocumentState getFile(TextDocumentIdentifier doc) {
        return getFile(Locations.toLoc(doc));
    }

    private TextDocumentState getFile(ISourceLocation loc) {
        TextDocumentState file = files.get(loc);
        if (file == null) {
            throw new ResponseErrorException(new ResponseError(-1, "Unknown file: " + loc, loc));
        }
        return file;
    }

    public void shutdown() {
        ownExecuter.shutdown();
    }

    private CompletableFuture<SemanticTokens> getSemanticTokens(TextDocumentIdentifier doc) {
        return recoverExceptions(getFile(doc).getCurrentTreeAsync()
                .thenApplyAsync(tokenizer::semanticTokensFull, ownExecuter)
                .whenComplete((r, e) ->
                    logger.trace("Semantic tokens success, reporting {} tokens back", r == null ? 0 : r.getData().size() / 5)
                )
            , () -> new SemanticTokens(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        logger.debug("semanticTokensFull: {}", params.getTextDocument());
        return getSemanticTokens(params.getTextDocument());
    }

    @Override
    public CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> semanticTokensFullDelta(
            SemanticTokensDeltaParams params) {
        logger.debug("semanticTokensFullDelta: {}", params.getTextDocument());
        return getSemanticTokens(params.getTextDocument()).thenApply(Either::forLeft);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        logger.debug("semanticTokensRange: {}", params.getTextDocument());
        return getSemanticTokens(params.getTextDocument());
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>documentSymbol(DocumentSymbolParams params) {
        logger.debug("Outline/documentSymbols: {}", params.getTextDocument());

        final TextDocumentState file = getFile(params.getTextDocument());
        ILanguageContributions contrib = contributions(params.getTextDocument());
        return recoverExceptions(file.getCurrentTreeAsync()
            .thenApply(contrib::outline)
            .thenCompose(InterruptibleFuture::get)
            .thenApply(c -> Outline.buildOutline(c, columns.get(file.getLocation())))
            , Collections::emptyList);
    }

    private ParametricSummaryBridge summary(TextDocumentIdentifier doc) {
        return facts(doc).getSummary(Locations.toLoc(doc));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        logger.debug("Definition: {} at {}", params.getTextDocument(), params.getPosition());
        return recoverExceptions(summary(params.getTextDocument())
            .getDefinition(params.getPosition())
            .thenApply(d -> { logger.debug("Definitions: {}", d); return d;})
            .thenApply(Either::forLeft)
            , () -> Either.forLeft(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            ImplementationParams params) {
        logger.debug("Implementation: {} at {}", params.getTextDocument(), params.getPosition());

        return recoverExceptions(summary(params.getTextDocument())
            .getImplementations(params.getPosition())
            .thenApply(Either::forLeft)
            , () -> Either.forLeft(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        logger.debug("Implementation: {} at {}", params.getTextDocument(), params.getPosition());

        return recoverExceptions(summary(params.getTextDocument())
            .getReferences(params.getPosition())
            .thenApply(l -> l) // hack to help compiler see type
            , Collections::emptyList);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        logger.debug("Hover: {} at {}", params.getTextDocument(), params.getPosition());

        return recoverExceptions(summary(params.getTextDocument())
            .getHover(params.getPosition())
            .thenApply(Hover::new)
            , () -> null);
    }

    @Override
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        logger.debug("textDocument/foldingRange: {}", params.getTextDocument());
        TextDocumentState file = getFile(params.getTextDocument());
        return recoverExceptions(file.getCurrentTreeAsync().thenApplyAsync(FoldingRanges::getFoldingRanges)
            .whenComplete((r, e) ->
                logger.trace("Folding regions success, reporting {} regions back", r == null ? 0 : r.size())
            ), Collections::emptyList);
    }

    @Override
    public void registerLanguage(LanguageParameter lang) {
        logger.info("registerLanguage({})", lang.getName());


        var multiplexer = contributions.computeIfAbsent(lang.getExtension(),
            t -> new LanguageContributionsMultiplexer(lang.getName(), lang.getExtension(), ownExecuter)
        );
        var fact = facts.computeIfAbsent(lang.getExtension(), t ->
            new ParametricFileFacts(multiplexer, this::getFile, columns, ownExecuter)
        );

        multiplexer.addContributor(buildContributionKey(lang),
            new InterpretedLanguageContributions(lang, this, workspaceService, (IBaseLanguageClient) client, ownExecuter));

        fact.reloadContributions();
        if (client != null) {
            fact.setClient(client);
        }
    }

    private static String buildContributionKey(LanguageParameter lang) {
        return lang.getMainFunction() + "::" + lang.getMainFunction();
    }

    @Override
    public void unregisterLanguage(LanguageParameter lang) {
        var extension = lang.getExtension();
        if (lang.getMainModule() == null || lang.getMainModule().isEmpty()) {
            // clear the whole language
            logger.trace("unregisterLanguage({}) completly", lang.getName());
            facts.remove(extension);
            contributions.remove(extension);
            return;
        }
        logger.trace("unregisterLanguage({}) only {}", lang.getName(), lang.getMainModule());
        if (!contributions.get(extension).removeContributor(buildContributionKey(lang))) {
            logger.error("unregisterLanguage cleared everything, so removing all");
            facts.remove(extension);
            contributions.remove(extension);
        }
        else {
            facts.get(extension).reloadContributions();
        }
    }

    @Override
    public CompletableFuture<IValue> executeCommand(String extension, String command) {
        ILanguageContributions contribs = contributions.get(extension);

        if (contribs != null) {
            return contribs.executeCommand(command).get();
        }
        else {
            logger.warn("ignoring command execution: " + extension + "," + command);
            return CompletableFuture.completedFuture(null);
        }
    }

}
