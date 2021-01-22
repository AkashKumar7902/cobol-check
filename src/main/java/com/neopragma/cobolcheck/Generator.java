/*
Copyright 2020 David Nicolette

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.neopragma.cobolcheck;

import com.neopragma.cobolcheck.exceptions.CobolSourceCouldNotBeReadException;
import com.neopragma.cobolcheck.exceptions.PossibleInternalLogicErrorException;

import java.io.*;
import java.util.*;

/**
 * This class merges a Test Suite (a text file) with the source of the Cobol program to be tested,
 * producing a Cobol program with the unit test cases embedded in it.
 *
 * @author Dave Nicolette (neopragma)
 * @since 14
 */
public class Generator implements Constants, StringHelper {
    //TODO simplify this:the injected Config object contains an instance of Messages; no need for another one
    private final Messages messages;
    private final Config config;
    private final TokenExtractor tokenExtractor;
    private final TestSuiteParser testSuiteParser;

    private final State state = new State();

    // All lines from original Environment Division / Input-Output Section / File Control
    private List<String> fileControlStatements;

    // All lines from original Data Division / File Section
    private List<String> fileSectionStatements;

    // Internal file identifiers and status field names
    private Map<String, String> fileIdentifiersAndStatuses;

    // Tokens collected from COPY statements that may span multiple lines
    private List<String> copyTokens;

    // Optionally replace identifier prefixes in cobol-check copybook lines and generated source lines,
    // in case of conflict with prefixes used in programs to be tested.
    // This is set in config.properties, cobolcheck.prefix entry.
    private String testCodePrefix;

    // Used for handling source lines from copybooks that may not have the standard 80-byte length
    private static final int minimumMeaningfulSourceLineLength = 7;
    private static final int commentIndicatorOffset = 6;
    private static final char commentIndicator = '*';

    // The boilerplate copybooks for cobol-check test code inserted into Working-Storage and Procedure.
    // The names are a throwback to the proof-of-concept project, cobol-unit-test. Might change in future.
    private static final String workingStorageCopybookFilename = "ZUTZCWS.CPY";
    private static final String procedureDivisionCopybookFilename = "ZUTZCPD.CPY";
    // Comes from config.properties, cobolcheck.copybook.directory entry.
    private static String copybookDirectoryName = EMPTY_STRING;
    // Used to read source lines from cobol-check copybooks (as opposed to reading the program under test)
    private Reader secondarySourceReader;

    // Used to handle programs that don't have a Working-Storage Section
    private boolean workingStorageTestCodeHasBeenInserted = false;
    private final String workingStorageHeader = fixedLength("       WORKING-STORAGE SECTION.");

    // used while processing SELECT statements in the program under test
    String fileIdentifier = EMPTY_STRING;
    boolean expectFileIdentifier;

    // Flags to keep track of context while reading input source files.
    // We want to make a single pass of all inputs, so we need to know what we are looking for at any given point.
    private boolean readingFileControl;
    private boolean readingFileSection;
    private boolean skipThisLine;
    private boolean expectFileStatusFieldName;
    private boolean processingFD;
    private boolean processing01ItemUnderFD;
    private boolean processingCopyStatement;

    public Generator(
            KeywordExtractor keywordExtractor,
            Config config) {
        this.config = config;
        this.messages = config.getMessages();
        this.tokenExtractor = new StringTokenizerExtractor(messages);
        testSuiteParser = new TestSuiteParser(keywordExtractor, config);
        copybookDirectoryName = setCopybookDirectoryName(config);
        testCodePrefix = config.getString(COBOLCHECK_PREFIX_CONFIG_KEY, DEFAULT_COBOLCHECK_PREFIX);
        fileIdentifiersAndStatuses = new HashMap<>();
    }

    /**
     * Merge test code with the program under test to produce a Cobol source program
     * that can be compiled and executed to run the test suite.
     *
     * @param testSuite (Reader) Test cases
     * @param cobolSourceIn (Reader) Source of Cobol program under test
     * @param testSourceOut (Writer) Cobol source with test cases merged into program under test
     * @return (Writer) Same Writer object as passed in, populated with Cobol source lines
     */
    public Writer mergeTestSuite(
            Reader testSuite,
            Reader cobolSourceIn,
            Writer testSourceOut) {

        if (testSuite == null) {
            throw new PossibleInternalLogicErrorException(
                    messages.get("ERR001", "testSuite", "Generator.runSuite()"));
        }
        BufferedReader testSuiteReader
                = new BufferedReader(testSuite);
        if (cobolSourceIn == null) {
            throw new PossibleInternalLogicErrorException(
                    messages.get("ERR001", "cobolSourceIn", "Generator.runSuite()"));
        }
        BufferedReader cobolSourceInReader
                = new BufferedReader(cobolSourceIn);
        String sourceLine;
        boolean emptyInputStream = true;
        try {
            while ((sourceLine = cobolSourceInReader.readLine()) != null) {
                emptyInputStream = false;
                sourceLine = fixedLength(sourceLine);
                List<String> tokens = tokenExtractor.extractTokensFrom(sourceLine);

                processingBeforeEchoingSourceLineToOutput(
                        tokens, sourceLine, cobolSourceInReader, testSourceOut);

                if (!skipThisLine) {
                    testSourceOut.write(sourceLine);
                }

                processingAfterEchoingSourceLineToOutput(
                        tokens, sourceLine, testSuiteReader, cobolSourceInReader, testSourceOut);
            }
            cobolSourceInReader.close();
        } catch (IOException ioEx) {
            throw new CobolSourceCouldNotBeReadException(ioEx);
        }
            catch (Exception ex) {
            throw new PossibleInternalLogicErrorException(ex);
        }
        if (emptyInputStream) {
            throw new PossibleInternalLogicErrorException(messages.get("ERR007"));
        }
        return testSourceOut;
    }

    /**
     * Change the state of the merge process depending on which section of the program under test we have reached.
     * This is how we know which kinds of source statements to look for when parsing the program source.
     *
     * @param partOfProgram - the division, section, paragraph, sentence, or clause we are processing at the moment.
     */
    private void entering(String partOfProgram) {
        state.getFlags().get(partOfProgram).set();
    }

    /**
     * Perform appropriate processing of the current input line from the program under test prior to echoing that
     * line to the test program (that is, the copy of the program under test that has test code injected into it).
     *
     * @param tokens - extracted from the current source line
     * @param sourceLine - the current source line
     * @param reader - the reader attached to the source of the program under test
     * @param testSourceOut - the writer attached to the test program being generated
     * @throws IOException - pass any IOExceptions up to the caller
     */
    private void processingBeforeEchoingSourceLineToOutput(
            List<String> tokens,
            String sourceLine,
            Reader reader,
            Writer testSourceOut) throws IOException {


        System.out.println("just read sourceLine: <" + sourceLine + ">");

        skipThisLine = false;

        if (state.getFlags().get(FILE_CONTROL).isSet()) {
            if (expectFileStatusFieldName) {
                if (tokens.size() > 0) {
                    fileIdentifiersAndStatuses.put(fileIdentifier, tokens.get(0));
                    expectFileStatusFieldName = false;
                }
            }

            // When the current source line contains FILE STATUS, the next tokens will be [IS] FIELDNAME.
            // Those tokens may be coded on the same line or on subsequent lines in the source program.
            if (sourceLineContains(tokens, FILE_STATUS_TOKEN)) {
                if (tokens.size() > 2) {
                    if (tokens.get(1).equalsIgnoreCase(IS_TOKEN)) {
                        fileIdentifiersAndStatuses.put(fileIdentifier, tokens.get(2));
                    }
                } else {
                    if (tokens.size() > 1) {

                        if (tokens.get(1).equalsIgnoreCase(IS_TOKEN)) {
                            expectFileStatusFieldName = true;
                        } else {
                            fileIdentifiersAndStatuses.put(fileIdentifier, tokens.get(1));
                        }
                    } else {
                        expectFileStatusFieldName = true;
                    }
                }
            }
        }

        // We expect the next token from the source program to be the file identifier associated with the
        // most recent SELECT statement we encountered. It will become a key in a map of file identifiers
        // to file status field names.
        if (expectFileIdentifier) {
            if (tokens.size() > 0) {
                fileIdentifier = tokens.get(0);
                fileIdentifiersAndStatuses.put(fileIdentifier, EMPTY_STRING);
                expectFileIdentifier = false;
            }
        }

        if (sourceLineContains(tokens, ENVIRONMENT_DIVISION)) entering(ENVIRONMENT_DIVISION);

        if (sourceLineContains(tokens, CONFIGURATION_SECTION)) {
            entering(CONFIGURATION_SECTION);
            readingFileControl = false;
            skipThisLine = false;
        }

        if (sourceLineContains(tokens, INPUT_OUTPUT_SECTION)) entering(INPUT_OUTPUT_SECTION);

        if (sourceLineContains(tokens, FILE_CONTROL)) {
            entering(FILE_CONTROL);
            readingFileControl = true;
            fileControlStatements = new ArrayList<>();
        }

        if (readingFileControl) {
            processFileControlSource(tokens, sourceLine);
        }

        if (sourceLineContains(tokens, FILE_SECTION)) {
            entering(FILE_SECTION);
            readingFileSection = true;
            fileSectionStatements = new ArrayList<>();
        }

        if (readingFileSection) {
            processFileSectionSource(tokens, sourceLine);
        }

        if (sourceLineContains(tokens, DATA_DIVISION)) {
            entering(DATA_DIVISION);
            skipThisLine = false;
            readingFileControl = false;
        }

        if (sourceLineContains(tokens, PROCEDURE_DIVISION)) {
            entering(PROCEDURE_DIVISION);
            if (!workingStorageTestCodeHasBeenInserted) {
                testSourceOut.write(workingStorageHeader);
                insertWorkingStorageTestCode(testSourceOut);
            }
        }
        if (sourceLineContains(tokens, WORKING_STORAGE_SECTION)) {
            entering(WORKING_STORAGE_SECTION);
            skipThisLine = false;
            readingFileSection = false;
        }
    }

    /**
     * Perform appropriate processing after echoing (or skipping) the current source line from the program under test.
     *
     * @param tokens - extracted from the current source line
     * @param sourceLine - the original source line
     * @param testSuiteReader - reader attached to the user-written test suite
     * @param reader - reader attached to the source of the program under test
     * @param testSourceOut - writer attached to the test program being generated
     * @throws IOException - pass any IOExceptions to the caller
     */
    private void processingAfterEchoingSourceLineToOutput(
            List<String> tokens,
            String sourceLine,
            BufferedReader testSuiteReader,
            Reader reader,
            Writer testSourceOut) throws IOException {

        if (sourceLineContains(tokens, WORKING_STORAGE_SECTION)) {
            insertWorkingStorageTestCode(testSourceOut);
        }

        if (sourceLineContains(tokens, PROCEDURE_DIVISION)) {
            insertProcedureDivisionTestCode(testSuiteReader, testSourceOut);
        }
    }

    /**
     * Called for each source line read from the program under test while processing the File Section of the
     * Data Division. We capture source statements that define record layouts so that we can copy them into
     * the Working-Storage Section later. These statements may use optional Cobol keywords and they may be
     * coded on multiple source lines. We also need to expand any copybooks referenced in this part of the
     * source, in case data items in the copybooks are referenced by user-written test cases.
     *
     * @param tokens - tokens extracted from source line.
     * @param sourceLine - original source line.
     */
    void processFileSectionSource(List<String> tokens, String sourceLine) {
        if (isTooShortToBeMeaningful(sourceLine)) {
            skipThisLine = true;
            return;
        }
        if (isComment(sourceLine)) {
            skipThisLine = true;
            return;
        }
        if (sourceLineContains(tokens, FD_TOKEN)) {
            processingFD = true;
        }
        if (processingFD) {
            if (sourceLineContains(tokens, LEVEL_01_TOKEN)) {
                processing01ItemUnderFD = true;
                processingCopyStatement = false;
            } else {
                if (sourceLineContains(tokens, COPY_TOKEN)) {
                    // Collect the tokens that constitute the Copy statement
                    copyTokens = accumulateTokensFromCopyStatement(copyTokens, sourceLine);
                    if (sourceLine.trim().endsWith(".")) {
                        // COPY statement is complete on this line
                        fileSectionStatements.addAll(collectExpandedCopyStatements(copyTokens));
                        processingCopyStatement = false;
                    } else {
                        // COPY statement is coded across multiple lines
                        processingCopyStatement = true;
                    }
                    skipThisLine = true;
                    return;
                }
            }
            if (processing01ItemUnderFD) {
                if (sourceLineContains(tokens, WORKING_STORAGE_SECTION)
                        || sourceLineContains(tokens, LOCAL_STORAGE_SECTION)
                        || sourceLineContains(tokens, LINKAGE_SECTION)
                        || sourceLineContains(tokens, PROCEDURE_DIVISION)) {
                    processingFD = false;
                    processing01ItemUnderFD = false;
                } else {
                    if (sourceLineContains(tokens, FD_TOKEN)) {
                        processing01ItemUnderFD = false;
                        skipThisLine = true;
                        return;
                    } else {
                        if (processingCopyStatement) {
                            copyTokens = accumulateTokensFromCopyStatement(copyTokens, sourceLine);
                            if (sourceLine.trim().endsWith(".")) {
                                // Multi-line COPY statement ends on this line
                                fileSectionStatements.addAll(collectExpandedCopyStatements(copyTokens));
                                processingCopyStatement = false;
                            }
                            skipThisLine = true;
                            return;
                        }
                    }
                    // Record layout statements coded directly and not in a copybook
                    fileSectionStatements.add(sourceLine);
                }
            }
        }
        // Don't echo these lines to the test source program
        skipThisLine = true;
    }

    /**
     * If we are currently reading the FILE CONTROL paragraph of the program under test, look for specific
     * source lines that require explicit processing by cobol-check.
     * Specifically, we need to save the file identifiers associated with SELECT statements and store the
     * corresponding field names of FILE STATUS specifications in case they are referenced in user-written
     * test cases. We also need to copy any record layout items into Working-Storage, as storage for FD areas
     * will not be allocated when we stub out the OPEN statements for files.
     *
     * @param tokens - extracted from the current source line
     * @param sourceLine - the original source line
     */
    void processFileControlSource(List<String> tokens, String sourceLine) {
        skipThisLine = true;
        if (isTooShortToBeMeaningful(sourceLine)) {
            return;
        }
        fileControlStatements.add(sourceLine);

        // If the current line contains SELECT, then the next token on the same line or the first token on the
        // next line will be the file identifier. We will store the file identifier as the key in a map of
        // file identifiers and file status field names.
        if (sourceLineContains(tokens, SELECT_TOKEN)) {
            fileIdentifier = EMPTY_STRING;
            if (tokens.size() > 1) {
                fileIdentifier = tokens.get(1);
                fileIdentifiersAndStatuses.put(fileIdentifier, EMPTY_STRING);
            } else {
                expectFileIdentifier = true;
            }
        }
    }

    List<String> accumulateTokensFromCopyStatement(List<String> copyTokens, String sourceLine) {
        if (copyTokens == null) {
            copyTokens = new ArrayList<>();
        }
        String[] lineTokens = sourceLine.trim().split(SPACE);
        for (String lineToken : lineTokens) {
            if (lineToken != null && !lineToken.equals(EMPTY_STRING)) {
                copyTokens.add(lineToken);
            }
        }
        return copyTokens;
    }

    List<String> collectExpandedCopyStatements(List<String> copyTokens) {
        for (int i = 0 ; i < copyTokens.size() ; i++) {
            if (copyTokens.get(i).equals(EMPTY_STRING)) {
                copyTokens.remove(i);
            }
        }
        if (copyTokens.isEmpty()
                || !copyTokens.get(0).equalsIgnoreCase(COPY_TOKEN)
                || copyTokens.size() < 2) {
            throw new PossibleInternalLogicErrorException(messages.get("ERR024"));
        }
        List<String> copyLines = new ArrayList<>();

        // 2nd entry is the name of the copybook. The value might end with a period.
        String copybookName = copyTokens.get(1).replace(PERIOD, EMPTY_STRING);

        // 3rd entry might be the word "REPLACING" followed by "x" "BY" "y"
        StringTuple replacingValues = new StringTuple(null, null);
        if (copyTokens.size() > 4) {
            if (copyTokens.get(2).equalsIgnoreCase(REPLACING_KEYWORD)
            || copyTokens.get(4).equalsIgnoreCase(BY_KEYWORD)) {
                replacingValues = new StringTuple(copyTokens.get(3), copyTokens.get(5));
            }
        }

        StringWriter expandedLines = new StringWriter();
        CopybookExpander copybookExpander = new CopybookExpander(config, messages);
        try {
            expandedLines = (StringWriter) copybookExpander.expand(
                    expandedLines,
                    copybookName,
                    PERIOD + config.getString(
                            APPLICATION_COPYBOOK_FILENAME_SUFFIX_KEY,
                            DEFAULT_APPLICATION_COPYBOOK_FILENAME_SUFFIX),
                    replacingValues);
            BufferedReader reader = new BufferedReader(new StringReader(expandedLines.toString()));
            String line = reader.readLine();
            while(line != null) {
                copyLines.add(line);
                line = reader.readLine();
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
        return copyLines;
    }

    /**
     * @param sourceLine
     * @return true if the source line "looks like" a Cobol comment line.
     */
    private boolean isComment(String sourceLine) {
        return sourceLine.charAt(commentIndicatorOffset) == commentIndicator;
    }

    /**
     * This "shouldn't happen." Famous last words.
     *
     * @param sourceLine
     * @return true if the source line is too short to be a meaningful line of code in Cobol.
     */
    private boolean isTooShortToBeMeaningful(String sourceLine) {
        return sourceLine == null || sourceLine.length() < minimumMeaningfulSourceLineLength;
    }

    /**
     * Insert test code into the Working-Storage Section of the test program being generated.
     *
     * @param testSourceOut - writer attached to the test program being generated.
     * @throws IOException - pass any IOExceptions to the caller.
     */
    private void insertWorkingStorageTestCode(Writer testSourceOut) throws IOException {
        // If this program had File Section source that we need to move to Working-Storage, inject the lines here.
        if (fileSectionStatements != null) {
            for (String line : fileSectionStatements) {
                testSourceOut.write(fixedLength(line));
            }
        }

        // Inject boilerplate test code from cobol-check Working-Storage copybook
        secondarySourceReader = new FileReader(copybookFile(workingStorageCopybookFilename));
        insertSecondarySourceIntoTestSource(testSourceOut);
        workingStorageTestCodeHasBeenInserted = true;
    }

    /**
     * Insert test code into the Procedure Division of the test program being generated.
     *
     * @param testSourceOut - writer attached to the test program being generated.
     * @throws IOException - pass any IOExceptions to the caller.
     */
    private void insertProcedureDivisionTestCode(
            BufferedReader testSuiteReader,
            Writer testSourceOut) throws IOException {
        // Inject test initialization statement
        testSuiteParser.insertTestInitializationLineIntoTestSource(testSourceOut);

        // Parse the concatenated test suite and insert generated Cobol test statements
        testSuiteParser.parseTestSuite(testSuiteReader, testSourceOut);

        // Inject boilerplate test code from cobol-check Procedure Division copybook
        secondarySourceReader = new FileReader(copybookFile(procedureDivisionCopybookFilename));
        insertSecondarySourceIntoTestSource(testSourceOut);
    }

    /**
     * Inject source statements from a secondary source (not the program under test) into the test program
     * being generated. Secondary sources are the cobol-check boilerplate copybooks, one for Working-Storage
     * and one for Procedure Division.
     *
     * @param testSourceOut - writer attached to the test program being generated.
     * @throws IOException - pass any IOExceptions to the caller.
     */
    private void insertSecondarySourceIntoTestSource(Writer testSourceOut) throws IOException {
        BufferedReader secondarySourceBufferedReader = new BufferedReader(secondarySourceReader);
        String secondarySourceLine = EMPTY_STRING;
        while ((secondarySourceLine = secondarySourceBufferedReader.readLine()) != null) {
            secondarySourceLine = secondarySourceLine
                    .replaceAll(TEST_CODE_PREFIX_PLACEHOLDER, testCodePrefix);
            testSourceOut.write(fixedLength(secondarySourceLine));
        }
        secondarySourceBufferedReader.close();
    }

    /**
     * Enclose a value in quotation marks.
     *
     * @param value - original string
     * @return - quoted string
     */
    String quoted(String value) {
        StringBuffer buffer = new StringBuffer();
        buffer.append(QUOTE);
        buffer.append(value);
        buffer.append(QUOTE);
        return buffer.toString();
    }

    private boolean sourceLineContains(List<String> tokens, String tokenValue) {
        return tokens.size() > 0 && tokens.contains(tokenValue.toUpperCase(Locale.ROOT));
    }

    private File copybookFile(String fileName) {
        return new File(copybookDirectoryName + fileName);
    }

    private String setCopybookDirectoryName(Config config) {
        return config.getString(RESOURCES_DIRECTORY_CONFIG_KEY)
                + Constants.FILE_SEPARATOR
                + this.getClass().getPackageName().replace(".", "/")
                + Constants.FILE_SEPARATOR
                + config.getString(COBOLCHECK_COPYBOOK_DIRECTORY_CONFIG_KEY)
                + Constants.FILE_SEPARATOR;
    }

}
