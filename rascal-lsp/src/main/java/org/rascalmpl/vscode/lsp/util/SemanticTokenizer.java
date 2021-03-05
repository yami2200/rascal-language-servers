package org.rascalmpl.vscode.lsp.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensCapabilities;
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests;
import org.eclipse.lsp4j.SemanticTokensDelta;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.rascalmpl.values.parsetrees.ITree;
import org.rascalmpl.values.parsetrees.ProductionAdapter;
import org.rascalmpl.values.parsetrees.TreeAdapter;

import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.IValue;

public class SemanticTokenizer implements ISemanticTokens {

    @Override
    public SemanticTokens semanticTokensFull(ITree tree) {
        TokenList tokens = new TokenList();
        new TokenCollector(tokens).collect(tree);
        return new SemanticTokens(tokens.getTheList());
    }

    @Override
    public Either<SemanticTokens, SemanticTokensDelta> semanticTokensFullDelta(String previousId, ITree tree) {
       return Either.forLeft(semanticTokensFull(tree));
    }

    @Override
    public SemanticTokens semanticTokensRange(Range range, ITree tree) {
        return semanticTokensFull(tree);
    }

    @Override
    public SemanticTokensWithRegistrationOptions options() {
        SemanticTokensWithRegistrationOptions result = new SemanticTokensWithRegistrationOptions();
        SemanticTokensLegend legend = new SemanticTokensLegend(TokenTypes.getTokenTypes(), TokenTypes.getTokenModifiers());

        result.setFull(true);
        result.setLegend(legend);

        return result;
    }

    @Override
    public SemanticTokensCapabilities capabilities() {
        SemanticTokensClientCapabilitiesRequests requests = new SemanticTokensClientCapabilitiesRequests(true);
        SemanticTokensCapabilities cps = new SemanticTokensCapabilities(
            requests,
            TokenTypes.getTokenTypes(),
            Collections.emptyList(),
            Collections.emptyList());

        return cps;
    }

    private static class TokenList {
        List<Integer> theList = new ArrayList<>(500);
        int previousLine = 0;
        int previousStart = 0;

        public List<Integer> getTheList() {
            return Collections.unmodifiableList(theList);
        }

        public void addToken(int startLine, int startColumn, int length, String category) {
            // https://microsoft.github.io/language-server-protocol/specifications/specification-3-16/#textDocument_semanticTokens
            theList.add(startLine - previousLine);
            theList.add(startLine == previousLine ? startColumn - previousStart : startColumn);
            theList.add(length);
            theList.add(TokenTypes.tokenTypeForName(category));
            theList.add(0); // no support for modifiers yet
            previousLine = startLine;
            previousStart = startColumn;
        }
    }

    private static class TokenTypes {
        private static final Map<String, Integer> cache = new HashMap<>();

        private static String[] types = new String[] { 
            TreeAdapter.NORMAL, 
            TreeAdapter.TYPE, 
            TreeAdapter.IDENTIFIER, 
            TreeAdapter.VARIABLE, 
            TreeAdapter.CONSTANT, 
            TreeAdapter.COMMENT,
            TreeAdapter.TODO, 
            TreeAdapter.QUOTE, 
            TreeAdapter.META_AMBIGUITY, 
            TreeAdapter.META_VARIABLE, 
            TreeAdapter.META_KEYWORD, 
            TreeAdapter.META_SKIPPED, 
            TreeAdapter.NONTERMINAL_LABEL,
            TreeAdapter.RESULT, 
            TreeAdapter.STDOUT, 
            TreeAdapter.STDERR 
        };

        /**
         * translates the Rascal category types to tmGrammar token names
         */
        private static String[] tmTokenNames = new String[] {
            "???",
            "type",
            "identifier",
            "variable",
            "constant",
            "comment",
            "comment.todo",
            "string.double",
            "invalid.illegal",
            "variable",
            "keyword.control",
            "invalid.illegal",
            "variable.parameter",
            "constant",
            "constant",
            "constant"
        };

        static {
            for (int i = 0; i < types.length; i++) {
                cache.put(types[i], i);
            }
        }

        public static List<String> getTokenTypes() {
            return Arrays.asList(tmTokenNames);
        }        
        
        public static List<String> getTokenModifiers() {
            return Collections.emptyList();
        }

        public static int tokenTypeForName(String category) {
            Integer result = cache.get(category);

            return result != null ? result : 0;
        }
    }

    private static class TokenCollector {
        private int location;
        private int line;
        private int column;

        private final boolean showAmb = false;
        private TokenList tokens;

        public TokenCollector(TokenList tokens) {
            super();
            this.tokens = tokens;
            location = 0;
            line = 0;
            column = 0;
        }

        public void collect(ITree tree) {
            collect(tree, false);
        }

        private void collect(ITree tree, boolean skip) {
            if (tree.isAppl()) {
                collectAppl(tree, skip);
            }
            else if (tree.isAmb()) {
                collectAmb(tree, skip);
            }
            else if (tree.isChar()) {
                collectChar(tree, skip);
            }
        }

        private void collectAppl(ITree arg, boolean skip) {
            String category = null;

            if (!skip) {
                IValue catAnno = arg.asWithKeywordParameters().getParameter("category");

                if (catAnno != null) {
                    category = ((IString) catAnno).getValue();
                }

                IConstructor prod = TreeAdapter.getProduction(arg);

                if (category == null && ProductionAdapter.isDefault(prod)) {
                    category = ProductionAdapter.getCategory(prod);
                }

                if (category == null && ProductionAdapter.isDefault(prod) && (TreeAdapter.isLiteral(arg) || TreeAdapter.isCILiteral(arg))) {
                    category = TreeAdapter.META_KEYWORD;
    
                    // unless this is an operator
                    for (IValue child : TreeAdapter.getArgs(arg)) {
                        int c = TreeAdapter.getCharacter((ITree) child);
                        if (c != '-' && !Character.isJavaIdentifierPart(c)) {
                            category = null;
                        }
                    }
                }
            }

            int startLine = line;
            int startColumn = column;

            // now we go down in the tree to find more tokens and to advance the counters
            for (IValue child : TreeAdapter.getArgs(arg)) {
                collect((ITree) child, skip || category != null);
            }

            if (category != null && !skip) {
                tokens.addToken(startLine, startColumn, column -startColumn, category);
            }
        }

        private void collectAmb(ITree arg, boolean skip) {
            if (showAmb) {
                int offset = location;
                ISourceLocation ambLoc = TreeAdapter.getLocation(arg);
                int length = ambLoc != null ? ambLoc.getLength() : TreeAdapter.yield(arg).length();

                location += length;
                tokens.addToken(ambLoc.getBeginLine() - 1, ambLoc.getBeginColumn(), ambLoc.getLength(), "MetaAmbiguity");
            } else {
                collect((ITree) TreeAdapter.getAlternatives(arg).iterator().next(), skip);
            }
        }

        private void collectChar(ITree ch, boolean skip) {
            location++;

            if (TreeAdapter.getCharacter(ch) == '\n') {
                line++;
                column = 0;
            }
            else {
                column++;
            }
        }
    }
}
