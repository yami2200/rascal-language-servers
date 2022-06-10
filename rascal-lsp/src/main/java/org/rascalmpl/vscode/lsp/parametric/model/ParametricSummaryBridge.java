/*
 * Copyright (c) 2018-2022, NWO-I CWI and Swat.engineering
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
package org.rascalmpl.vscode.lsp.parametric.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.rascalmpl.values.IRascalValueFactory;
import org.rascalmpl.values.parsetrees.ITree;
import org.rascalmpl.values.parsetrees.TreeAdapter;
import org.rascalmpl.vscode.lsp.TextDocumentState;
import org.rascalmpl.vscode.lsp.parametric.ILanguageContributions;
import org.rascalmpl.vscode.lsp.util.Diagnostics;
import org.rascalmpl.vscode.lsp.util.Lazy;
import org.rascalmpl.vscode.lsp.util.concurrent.InterruptibleFuture;
import org.rascalmpl.vscode.lsp.util.concurrent.ReplaceableFuture;
import org.rascalmpl.vscode.lsp.util.locations.ColumnMaps;
import org.rascalmpl.vscode.lsp.util.locations.IRangeMap;
import org.rascalmpl.vscode.lsp.util.locations.Locations;
import org.rascalmpl.vscode.lsp.util.locations.impl.TreeMapLookup;
import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.IRelation;
import io.usethesource.vallang.ISet;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.ITuple;
import io.usethesource.vallang.IValue;
import io.usethesource.vallang.IWithKeywordParameters;
import io.usethesource.vallang.type.Type;
import io.usethesource.vallang.type.TypeFactory;
import io.usethesource.vallang.type.TypeStore;

public class ParametricSummaryBridge {

    private final Executor exec;
    private final ColumnMaps columns;
    private final ILanguageContributions contrib;
    private final Function<ISourceLocation, TextDocumentState> lookupState;
    private final ISourceLocation file;

    private final ReplaceableFuture<Lazy<List<Diagnostic>>> messages;
    private final CompletableFuture<LazyRangeMapCalculation<List<Location>>> definitions;
    private final CompletableFuture<LazyRangeMapCalculation<List<Location>>> implementations;
    private final CompletableFuture<LazyRangeMapCalculation<List<Location>>> references;
    private final CompletableFuture<LazyRangeMapCalculation<List<Either<String, MarkedString>>>> hovers;

    public ParametricSummaryBridge(Executor exec, ISourceLocation file, ColumnMaps columns,
        ILanguageContributions contrib, Function<ISourceLocation, TextDocumentState> lookupState) {
        this.exec = exec;
        this.file = file;
        this.columns = columns;
        this.contrib = contrib;
        this.lookupState = lookupState;
        definitions = contrib
            .hasDedicatedDefines()
            .thenCombine(contrib.askSummaryForDefinitions(), LazyDefinitions::new);
        implementations = contrib
            .hasDedicatedImplementations()
            .thenCombine(contrib.askSummaryForImplementations(), LazyImplementations::new);
        references = contrib
            .hasDedicatedReferences()
            .thenCombine(contrib.askSummaryForReferences(), LazyReferences::new);
        hovers = contrib.askSummaryForDocumentation().thenApply(RelationDocumentLookupMap::new);
        messages = ReplaceableFuture.completed(Lazy.defer(Collections::emptyList));
    }

    public void invalidate() {
        definitions.thenAccept(LazyRangeMapCalculation::invalidate);
        implementations.thenAccept(LazyRangeMapCalculation::invalidate);
        references.thenAccept(LazyRangeMapCalculation::invalidate);
        hovers.thenAccept(LazyRangeMapCalculation::invalidate);
    }

    private abstract class LazyRangeMapCalculation<T> {
        private final ReplaceableFuture<Lazy<IRangeMap<T>>> result;
        private volatile @Nullable InterruptibleFuture<Lazy<IRangeMap<T>>> lastFuture = null;

        protected final boolean dedicatedCall;
        protected final boolean checkSummary;
        protected final T empty;

        LazyRangeMapCalculation(boolean dedicatedCall, boolean checkSummary, T empty) {
            this.dedicatedCall = dedicatedCall;
            this.checkSummary = checkSummary;
            this.empty = empty;
            this.result = new ReplaceableFuture<>(
                CompletableFuture.completedFuture(
                    Lazy.defer(TreeMapLookup::emptyMap)));
        }

        public void invalidate() {
            var last = lastFuture;
            if (last != null) {
                last.interrupt();
                lastFuture = null;
            }
        }

        public void newSummary(InterruptibleFuture<IConstructor> summary) {
            if (!checkSummary) {
                return;
            }
            replaceFuture(mapSummary(summary.thenApply(IConstructor::asWithKeywordParameters)));
        }

        private void replaceFuture(InterruptibleFuture<Lazy<IRangeMap<T>>> newFuture) {
            lastFuture = result.replace(newFuture);
        }

        abstract InterruptibleFuture<Lazy<IRangeMap<T>>> mapSummary(InterruptibleFuture<IWithKeywordParameters<? extends IConstructor>> newSummary);

        abstract InterruptibleFuture<T> requestDedicated(Position r);

        public InterruptibleFuture<T> lookup(Position cursor) {
            var activeSummary = lastFuture;
            if (activeSummary == null || !checkSummary) {
                if (dedicatedCall) {
                    return requestDedicated(cursor);
                }
                // else we can't do a thing anyway
                return InterruptibleFuture.completedFuture(empty);
            }
            return activeSummary
                .thenApplyAsync(Lazy::get, exec)
                .thenApply(l -> l.lookup(new Range(cursor, cursor)))
                .thenApply(r -> r == null ? this.empty : r);
        }


    }


    @FunctionalInterface
    public interface DedicatedLookupFunction {
        InterruptibleFuture<ISet> lookup(ISourceLocation file, ITree tree, ITree cursor);
    }

    private abstract class RelationLookupMap<T> extends LazyRangeMapCalculation<List<T>> {
        private final String kwField;
        private final DedicatedLookupFunction dedicatedCalcFunc;

        RelationLookupMap(boolean dedicatedCall, boolean checkSummary, String kwField, DedicatedLookupFunction dedicatedCalcFunc) {
            super(dedicatedCall, checkSummary, Collections.emptyList());
            this.kwField = kwField;
            this.dedicatedCalcFunc = dedicatedCalcFunc;
        }

        @Override
        InterruptibleFuture<Lazy<IRangeMap<List<T>>>> mapSummary(
            InterruptibleFuture<IWithKeywordParameters<? extends IConstructor>> newSummary) {
            return newSummary.thenApply(s ->
                Lazy.defer(() -> translateRelation(getKWFieldSet(s, kwField)))
            );
        }

        private IRelation<ISet> getKWFieldSet(IWithKeywordParameters<? extends IConstructor> data, String name) {
            if (data.hasParameter(name)) {
                return ((ISet) data.getParameter(name)).asRelation();
            }
            return IRascalValueFactory.getInstance().set().asRelation();
        }

        @Override
        InterruptibleFuture<List<T>> requestDedicated(Position cursor) {
            var result = lookupState.apply(file)
                    .getCurrentTreeAsync()
                    .thenApplyAsync(t -> {
                        var line = cursor.getLine() + 1;
                        var translatedOffset = columns.get(file).translateInverseColumn(line, cursor.getCharacter(), false);
                        var cursorTree = TreeAdapter.locateLexical(t, line, translatedOffset);
                        return dedicatedCalcFunc.lookup(file, t, cursorTree);
        }, exec);
            return InterruptibleFuture.flatten(result, exec)
                .thenApply(s -> {
                    if (s == null || s.isEmpty()) {
                        return Collections.emptyList();
                    }
                    int size = s.size();

                    if (size == 1) {
                        var v = s.iterator().next();
                        return Collections.singletonList(mapValue(v));
                    }
                    var locs = new ArrayList<T>(size);
                    for (IValue v : s) {
                        locs.add(mapValue(v));
                    }
                    return locs;
                });
        }

        protected abstract T mapValue(IValue v);



        IRangeMap<List<T>> translateRelation(IRelation<ISet> binaryRel) {
            TreeMapLookup<List<T>> result = new TreeMapLookup<>();
            for (IValue v: binaryRel) {
                ITuple row = (ITuple)v;
                Range from = Locations.toRange((ISourceLocation)row.get(0), columns);
                T to = mapValue(row.get(1));
                var existing = result.getExact(from);
                if (existing == null) {
                    // most cases there is only a single entry, to so save a lot of memory, we store a singleton list to start with
                    result.put(from, Collections.singletonList(to));
                }
                else if (existing.size() == 1) {
                    // we had a singleton list in there, so let's replace it with a regular list
                    existing = new ArrayList<>(existing);
                    result.put(from, existing);
                    existing.add(to);
                }
                else {
                    existing.add(to);
                }
            }
            return result;
        }
    }

    private class RelationLocationLookupMap extends RelationLookupMap<Location> {

        RelationLocationLookupMap(boolean dedicatedCall, boolean checkSummary, String kwField, DedicatedLookupFunction dedicatedCalcFunc) {
            super(dedicatedCall, checkSummary, kwField, dedicatedCalcFunc);
        }
        @Override
        protected Location mapValue(IValue v) {
            return Locations.toLSPLocation((ISourceLocation)v, columns);
        }
    }

    private class RelationDocumentLookupMap extends RelationLookupMap<Either<String, MarkedString>> {

        RelationDocumentLookupMap(boolean checkSummary) {
            super(false, checkSummary, "documentation", (x,y, z) -> null);
        }
        @Override
        protected Either<String, MarkedString> mapValue(IValue v) {
            return Either.forLeft(((IString) v).getValue());
        }
    }


    private class LazyDefinitions extends RelationLocationLookupMap {
        public LazyDefinitions(boolean dedicatedCall, boolean askSummary) {
            super(dedicatedCall, askSummary, "definitions", contrib::defines);
        }
    }

    private class LazyImplementations extends RelationLocationLookupMap {
        public LazyImplementations(boolean dedicatedCall, boolean askSummary) {
            super(dedicatedCall, askSummary, "implementations", contrib::implementations);
        }
    }

    private class LazyReferences extends RelationLocationLookupMap {
        public LazyReferences(boolean dedicatedCall, boolean askSummary) {
            super(dedicatedCall, askSummary, "references", contrib::references);
        }
    }

    public CompletableFuture<List<Location>> getDefinition(Position cursor) {
        return definitions.thenCompose(d -> d.lookup(cursor).get());
    }

    public CompletableFuture<List<Location>> getReferences(Position cursor) {
        return references.thenCompose(d -> d.lookup(cursor).get());
    }

    public CompletableFuture<List<Location>> getImplementations(Position cursor) {
        return implementations.thenCompose(d -> d.lookup(cursor).get());
    }

    public void calculateSummary() {
        var summary = InterruptibleFuture.flatten(lookupState.apply(file)
            .getCurrentTreeAsync()
            .thenApplyAsync(t -> contrib.summarize(file, t), exec)
            , exec);
        definitions.thenAccept(d -> d.newSummary(summary));
        references.thenAccept(d -> d.newSummary(summary));
        implementations.thenAccept(d -> d.newSummary(summary));
        hovers.thenAccept(d -> d.newSummary(summary));
        messages.replace(summary.thenApply(s -> Lazy.defer(() -> {
            var sum = s.asWithKeywordParameters();
            if (sum.hasParameter("messages")) {
                return ((ISet)sum.getParameter("messages")).stream()
                    .map(d -> Diagnostics.translateDiagnostic((IConstructor)(((ITuple)d).get(1)), columns))
                    .collect(Collectors.toList());
            }
            return Collections.emptyList();
        })));
    }

    public CompletableFuture<List<Either<String, MarkedString>>> getHover(Position cursor) {
        return hovers.thenCompose(h -> h.lookup(cursor).get());
    }

    public CompletableFuture<List<Diagnostic>> getMessages() {
        return messages.get().thenApply(Lazy::get);
    }

    private static final Type summaryCons;

    static {
        TypeFactory TF = TypeFactory.getInstance();
        TypeStore TS = new TypeStore();
        summaryCons = TF.constructor(TS, TF.abstractDataType(TS, "Summary"), "summary", TF.sourceLocationType(), "src");
    }

    public static IConstructor emptySummary(ISourceLocation src) {
        return IRascalValueFactory.getInstance().constructor(summaryCons, src);
    }

}
