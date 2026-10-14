import groovyjarjarantlr4.v4.runtime.*;
import groovyjarjarantlr4.v4.runtime.tree.ParseTree;
import org.apache.groovy.parser.antlr4.*;
import org.apache.groovy.parser.antlr4.internal.atnmanager.AtnManager;
import org.apache.groovy.parser.antlr4.util.StringUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Static declaration records from pinned CST; never compile, load or evaluate input. */
public final class GroovyCoordinateRecords {
    private record Literal(String value, int line) {}
    private static final Pattern NAME = Pattern.compile("^play-services-(?:analytics|measurement)[\\w-]*(?![\\w$])", Pattern.CASE_INSENSITIVE);
    private static final class SyntaxFault extends RuntimeException {}
    private static final ANTLRErrorListener<Integer> LEXER_FAIL = new ANTLRErrorListener<Integer>() {
        @Override public <T extends Integer> void syntaxError(Recognizer<T, ?> recognizer, T symbol, int line,
                                                             int column, String message, RecognitionException error) {
            throw new SyntaxFault();
        }
    };
    private static final BaseErrorListener PARSER_FAIL = new BaseErrorListener() {
        @Override public <T extends Token> void syntaxError(Recognizer<T, ?> recognizer, T symbol, int line,
                                                           int column, String message, RecognitionException error) {
            throw new SyntaxFault();
        }
    };

    private static String decode(String raw) {
        int width = raw.startsWith("\"\"\"") || raw.startsWith("'''") ? 3 : raw.startsWith("$/") ? 2 : 1;
        int mode = raw.startsWith("$/") ? StringUtils.DOLLAR_SLASHY : raw.startsWith("/") ? StringUtils.SLASHY : StringUtils.NONE_SLASHY;
        return StringUtils.replaceEscapes(StringUtils.removeCR(raw.substring(width, raw.length() - width)), mode);
    }

    private static Literal literal(ParserRuleContext node, CommonTokenStream stream, boolean label) {
        if (node == null) return null;
        List<Token> code = new ArrayList<>();
        for (Token token : stream.getTokens(node.getStart().getTokenIndex(), node.getStop().getTokenIndex())) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL && token.getType() != GroovyParser.NL) code.add(token);
        }
        if (label && code.size() == 1 && (code.get(0).getType() == GroovyParser.Identifier || code.get(0).getType() == GroovyParser.CapitalizedIdentifier)) {
            return new Literal(code.get(0).getText(), code.get(0).getLine());
        }
        // Strip only grouping that encloses the ENTIRE expression. String calls
        // such as "group"() and ("group")() are computed, not literal values.
        while (code.size() > 2 && code.get(0).getType() == GroovyParser.LPAREN) {
            int depth = 0, end = -1;
            for (int i = 0; i < code.size(); i++) {
                int type = code.get(i).getType();
                if (type == GroovyParser.LPAREN) depth++;
                else if (type == GroovyParser.RPAREN && --depth == 0) { end = i; break; }
            }
            if (end != code.size() - 1) break;
            code = code.subList(1, end);
        }
        if (code.size() != 1 || code.get(0).getType() != GroovyParser.StringLiteral) return null;
        Token value = code.get(0);
        return new Literal(decode(value.getText()), value.getLine());
    }

    private static void record(List<? extends GroovyParser.MapEntryContext> entries, CommonTokenStream stream, Set<Integer> lines) {
        Map<String, Literal> fields = new HashMap<>();
        for (GroovyParser.MapEntryContext entry : entries) {
            Literal key = literal(entry.mapEntryLabel(), stream, true);
            if (key == null || !(key.value.equals("group") || key.value.equals("name"))) continue;
            Literal value = literal(entry.expression(), stream, false);
            if (value == null) fields.remove(key.value);
            else fields.put(key.value, new Literal(value.value, key.line));
        }
        Literal group = fields.get("group"), name = fields.get("name");
        if (group != null && name != null && group.value.equalsIgnoreCase("com.google.android.gms") && NAME.matcher(name.value).find()) {
            lines.add(Math.min(group.line, name.line));
        }
    }

    private static SortedSet<Integer> lines(String source) throws IOException {
        // Constructors may take the ATN WRITE_LOCK. Construct both before
        // READ_LOCK, as pinned AstBuilder does; lock upgrade would deadlock.
        GroovyLangLexer lexer = new GroovyLangLexer(new StringReader(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(LEXER_FAIL);
        CommonTokenStream stream = new CommonTokenStream(lexer);
        GroovyLangParser parser = new GroovyLangParser(stream);
        parser.removeErrorListeners();
        parser.addErrorListener(PARSER_FAIL);
        AtnManager.READ_LOCK.lock();
        try {
            ParseTree root = parser.compilationUnit();
            stream.fill();
            if (parser.getNumberOfSyntaxErrors() != 0 || stream.LA(1) != Token.EOF) throw new SyntaxFault();
            SortedSet<Integer> result = new TreeSet<>();
            Deque<ParseTree> pending = new ArrayDeque<>();
            pending.push(root);
            while (!pending.isEmpty()) {
                ParseTree node = pending.pop();
                if (node instanceof GroovyParser.MapEntryListContext map) record(map.mapEntry(), stream, result);
                else if (node instanceof GroovyParser.EnhancedArgumentListInParContext arguments) {
                    List<GroovyParser.MapEntryContext> entries = new ArrayList<>();
                    for (GroovyParser.EnhancedArgumentListElementContext element : arguments.enhancedArgumentListElement()) {
                        if (element.mapEntry() != null) entries.add(element.mapEntry());
                    }
                    record(entries, stream, result);
                }
                for (int i = node.getChildCount() - 1; i >= 0; i--) pending.push(node.getChild(i));
            }
            return result;
        } finally { AtnManager.READ_LOCK.unlock(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 || !"3.0.22".equals(groovy.lang.GroovySystem.getVersion())) throw new IllegalStateException("parser_identity");
        System.out.println("GROOVY_RECORDS\t1\t3.0.22");
        int count = 0;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String row;
            while ((row = input.readLine()) != null) {
                String[] parts = row.split("\t", -1);
                if (parts.length != 2 || !parts[0].equals(Integer.toString(count))) throw new IllegalStateException("request_identity");
                String source = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                String status, value;
                try { value = lines(source).toString(); status = "grammar_parsed"; }
                catch (SyntaxFault | GroovySyntaxError | RecognitionException error) { status = "syntax_error"; value = "[]"; }
                catch (Throwable error) { status = "parser_failed"; value = "[]"; }
                System.out.println(count + "\t" + status + "\t" + value);
                count++;
            }
        }
        System.out.println("GROOVY_RECORDS_END\t" + count);
    }
}
