package com.ocient.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.Reader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.collections4.bag.TreeBag;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import com.google.common.collect.TreeMultimap;
import com.google.protobuf.util.JsonFormat;
import com.ocient.jdbc.DataEndMarker;
import com.ocient.jdbc.XGConnection;
import com.ocient.jdbc.XGDatabaseMetaData;
import com.ocient.jdbc.XGStatement;
import com.ocient.jdbc.proto.ClientWireProtocol.SysQueriesRow;
import com.ocient.jdbc.proto.PlanProtocol.PlanMessage;
import java.util.TimeZone;
import java.sql.Time;
import java.sql.Timestamp;

public class CLI {
	private static Connection conn;
	private static boolean timing = false;
	private static boolean trace = false;
	private static boolean performance = false;
	private static String outputCSVFile = "";
	private static String db;
	private static String user;
	private static String pwd;
	private static Collection<File> sources = new HashSet<File>();

	private static char quote = '\0';
	private static boolean comment = false;

	private static int BAG_FLUSH_SIZE = 8192;
	private static int MAX_BAGS_SIZE_PER_READER = 64 * 1024 * 1024;

	public static void main(final String[] args) {
		Terminal terminal;
		LineReader reader;
		try {
			Class.forName("com.ocient.jdbc.JDBCDriver");
		} catch (final Exception e) {
			System.out.println("Unable to load JDBC driver!");
			System.exit(1);
		}

		boolean echo = false;
		final Console cons = System.console();
		try {
			if (cons == null) {
				echo = true;
			}
			DefaultParser parser = new DefaultParser();
			//Prevents \ from disappearing and \\ from converting to \.  
			parser.setEscapeChars(null);
			terminal = TerminalBuilder.builder()
				.system(true)
				.build();
			reader = LineReaderBuilder.builder()
				.parser(parser)
				.terminal(terminal)
				.build();
		} catch (final IOException e) {
			System.out.println("Error setting up console");
			return;
		}

		// Usernames and passwords can contain any characters if appropriately quoted
		// (usernames use SQL case-sensitivity).
		// Quote for shell. Subsequent arguments are dropped.

		if (args.length == 0) {
			user = reader.readLine("Username: ");
		} else {
			user = args[0];
		}
		if (args.length < 2) {
			if (echo) {
				System.out.println();
			}
			pwd = reader.readLine("Password: ", '\0');
		} else {
			pwd = args[1];
		}
		System.out.println();

		boolean quit = false;
		String cmd = "";
		boolean scrubCmd = true;

		try {
			while (true) {
				// jline has ways to handle this, but they're underdocumented and overbuilt to
				// the point of obscenity
				if (!quit)
					cmd = reader.readLine("Ocient> ") + " ";
					if(startsWithIgnoreCase(cmd, "PLAN EXECUTE INLINE")) {
						//the scrubing logic looks for comments blocks in the SQL statement. Now, plans have a lots of 
						//double/single quotes, and those can be closed or not. scrubing a plans is not a good idea. It can cause the CLI to misinterpret quotes.  
						scrubCmd = false;
					}
					else {
						cmd = scrubCommand(cmd);
					}
				while (true) {
					if (quit || cmd.trim().equalsIgnoreCase("QUIT")) {
						try {
							if (conn != null && !conn.isClosed()) {
								conn.close();
							}
						} catch (final Exception e) {
						}
						if (echo) {
							System.out.println();
						}
						return;
					}
					if (echo) {
						System.out.println();
					}

					if (!comment && quote == '\0' && cmd.trim().endsWith(";")) {
						// System.out.println("Finished scrubbing command: '" + cmd + "'");
						cmd = cmd.trim();
						cmd = cmd.substring(0, cmd.length() - 1).trim();
						if (trace && !endsWithIgnoreCase(cmd, " trace")) {
							cmd = cmd + " trace";
						}
						// System.out.println("Finished trimming scrubbed: '" + cmd + "'");
						break;
					} else {
						//System.out.println("Current command text: '" + cmd + "'");
						String line = reader.readLine("(cont)> ") + " ";
						if(scrubCmd) {
							cmd += scrubCommand(line);
						}
						else {
							cmd += line;
						}
					}
				}

				quit = processCommand(cmd);
			}
		} catch (final UserInterruptException | EndOfFileException e) {
			return;
		}
	}

	private static String scrubCommand(String cmd) {
		final StringBuilder out = new StringBuilder(256);
		int i = 0;
		int length = cmd.length();
		while (i < length) {
			char c = cmd.charAt(i);
			if (!comment) {
				if (quote == '\0' && (i + 1) != length) {
					if (c == '-' && cmd.charAt(i + 1) == '-') {
						break;
					}
					if (c == '/' && cmd.charAt(i + 1) == '*') {
						comment = true;
						i++;
						continue;
					}
				}
				if ((c == '\'' || c == '"') && (quote == '\0' || quote == c)) // char is an active quote
				{
					quote = (quote == '\0') ? c : '\0';
				}
				out.append(c);
			} else if ((i + 1) != length && c == '*' && cmd.charAt(i + 1) == '/') {
				comment = false;
				i++;
			}

			i++;
		}
		return out.toString();
	}

	private static boolean processCommand(String cmd) {
		boolean quit = false;
		// System.out.println("processCommand(" + cmd + ")");
		if (cmd.equals("")) {
			return quit;
		}
		if (startsWithIgnoreCase(cmd, "CONNECT TO")) {
			connectTo(cmd);
		} else if (startsWithIgnoreCase(cmd, "SELECT") || startsWithIgnoreCase(cmd, "WITH")) {
			select(cmd);
		} else if (cmd.equalsIgnoreCase("TIMING ON")) {
			timing = true;
		} else if (cmd.equalsIgnoreCase("TIMING OFF")) {
			timing = false;
		} else if (cmd.equalsIgnoreCase("PERFORMANCE ON")) {
			performance = true;
			timing = true;
		} else if (cmd.equalsIgnoreCase("PERFORMANCE OFF")) {
			performance = false;
			timing = false;
		} else if (cmd.equalsIgnoreCase("TRACE ON")) {
			trace = true;
		} else if (cmd.equalsIgnoreCase("TRACE OFF")) {
			trace = false;
		} else if (startsWithIgnoreCase(cmd, "SET SCHEMA")) {
			setSchema(cmd);
		} else if (cmd.equalsIgnoreCase("GET SCHEMA")) {
			getSchema(cmd);
		} else if (startsWithIgnoreCase(cmd, "EXPLAIN")) {
			explain(cmd);
		} else if (startsWithIgnoreCase(cmd, "CREATE") || startsWithIgnoreCase(cmd, "DROP")
				|| startsWithIgnoreCase(cmd, "ALTER") || startsWithIgnoreCase(cmd, "TRUNCATE")
				|| startsWithIgnoreCase(cmd, "SET PSO")) {
			update(cmd);
		} else if (startsWithIgnoreCase(cmd, "LIST TABLES")) {
			listTables(cmd, false);
		} else if (startsWithIgnoreCase(cmd, "LIST SYSTEM TABLES")) {
			listTables(cmd, true);
		} else if (startsWithIgnoreCase(cmd, "LIST VIEWS")) {
			listViews(cmd);
		} else if (startsWithIgnoreCase(cmd, "DESCRIBE TABLE")) {
			describeTable(cmd);
		} else if (startsWithIgnoreCase(cmd, "DESCRIBE VIEW")) {
			describeView(cmd);
		} else if (startsWithIgnoreCase(cmd, "LIST INDICES") || startsWithIgnoreCase(cmd, "LIST INDEXES")) {
			listIndexes(cmd);
		} else if (startsWithIgnoreCase(cmd, "PLAN EXECUTE")) {
			executePlan(cmd);
		} else if (startsWithIgnoreCase(cmd, "PLAN EXPLAIN")) {
			explainPlan(cmd);
		} else if (startsWithIgnoreCase(cmd, "PLAN LIST")) {
			listPlan();
		} else if (startsWithIgnoreCase(cmd, "SOURCE")) {
			quit = source(cmd);
		} else if (startsWithIgnoreCase(cmd, "CANCEL")) {
			cancelQuery(cmd);
		} else if (startsWithIgnoreCase(cmd, "KILL")) {
			killQuery(cmd);
		} else if (startsWithIgnoreCase(cmd, "LIST ALL QUERIES")) {
			listAllQueries();
		} else if (startsWithIgnoreCase(cmd, "OUTPUT NEXT QUERY")) {
			outputNextQuery(cmd);
		} else if (startsWithIgnoreCase(cmd, "FORCE EXTERNAL")) {
			forceExternal(cmd);
		} else if (startsWithIgnoreCase(cmd, "EXPORT TABLE")) {
			exportTable(cmd);
                } else if (startsWithIgnoreCase(cmd, "EXPORT TRANSLATION")) {
                        exportTranslation(cmd);
		} else if (startsWithIgnoreCase(cmd, "SET TIMEOUT")) {
			setQueryTimeout(cmd);
		} else {
			System.out.println("Invalid command: " + cmd);
		}

		return quit;
	}

	private static boolean startsWithIgnoreCase(final String in, final String cmp) {
		int firstNonParentheses = 0;
		int len = in.length();
		while (firstNonParentheses < len && in.charAt(firstNonParentheses) == '(') {
			firstNonParentheses++;
		}
		if (in.substring(firstNonParentheses).toUpperCase().startsWith(cmp.toUpperCase())) {
			return true;
		}

		return false;
	}

	private static boolean endsWithIgnoreCase(final String in, final String cmp) {
		return in.toUpperCase().endsWith(cmp.toUpperCase());
	}

	// Generate a regex for an unquoted alphanumeric ([a-zA-Z0-9_]) or quoted free
	// (.) token. Reluctant.
	// Do not insert multiple regexes for tokens of the same name (or "q0" + another
	// name) into a single pattern.
	private static String tk(String name) {
		return "(?<q0" + name + ">\"?)(?<" + name + ">(\\w+?|(?<=\").+?(?=\")))\\k<q0" + name + ">";
	}

	// Generate a regex for an unquoted alphanumeric ([a-zA-Z0-9_]) or quoted free
	// (.) token possibly followed
	// by @ and more unquoted alphanumeric ([a-zA-Z0-9_]) or quoted free (.) tokens.
	private static String userTk() {
		return "(?<q0user>\"?)(?<user>(\\w+?|(?<=\").+?(?=\"))(@(\\w+?|(?<=\").+?(?=\")))?)\\k<q0user>";
	}

	// Get a token from its generated regex according to SQL case-sensitivity rules
	// (sensitive iff quoted).
	// Do not call on a matcher that has not yet called matches().
	private static String getTk(Matcher m, String name, String def) {
		if (m.group(name) == null) {
			return def;
		}
		if (m.group("q0" + name).length() == 0) {
			return m.group(name).toLowerCase();
		}
		return m.group(name);
	}

	private static Pattern connectToSyntax = Pattern
			.compile("connect\\s+to\\s+(?<preurl>jdbc:ocient://?)(?<hosts>.+?)(?<posturl>/.+?)(?<up>\\s+user\\s+(" + userTk()
					+ ")\\s+using\\s+(?<q>\"?)(?<pwd>.+?)\\k<q>)?(?<force>\\s+force)?", Pattern.CASE_INSENSITIVE);

	private static void connectTo(final String cmd) {
		if (isConnected()) {
			try {
				conn.close();
			} catch (final Exception e) {
				System.out.println("Error: " + e.getMessage());
				return;
			}
		}

		try {
			final Matcher m = connectToSyntax.matcher(cmd);
			if (!m.matches()) {
				System.out.println("Syntax: connect to <jdbc url>( user <username> using <password>)?( force)?");
				return;
			}
			final String hosts = m.group("hosts");
			final String preurl = m.group("preurl");
			final String posturl = m.group("posturl");

			Exception lastException = null;

			final String url = preurl + hosts + posturl;
			try {
				if (m.group("up") == null) {
					doConnect(user, pwd, (m.group("force") != null), url);
				} else {
					doConnect(getTk(m, "user", null), m.group("pwd"), (m.group("force") != null), url);
				}
				// No exception thrown means connection was successful, and connectTo may return
				return;
			} catch (final Exception e) {
				System.out.println("Failed to connect to " + hosts);
				throw e;
			}

		} catch (final Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static void doConnect(final String user, final String pwd, final boolean force, final String url)
			throws Exception {
		final Properties prop = new Properties();
		prop.setProperty("user", user);
		prop.setProperty("password", pwd);
		prop.setProperty("force", force ? "true" : "false");
		conn = DriverManager.getConnection(url, prop);
		CLI.db = ((XGConnection) conn).getDB();

		System.out.println("Connected to " + ((XGConnection) conn).getURL());
	}

	private static boolean isConnected() {
		if (conn != null) {
			return ((XGConnection) conn).connected();
		}
		return false;
	}

	private static void getSchema(final String cmd) {
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		try {
			String schema = conn.getSchema();
			if (!schema.toLowerCase().equals(schema)) {
				schema = "\"" + schema + "\"";
			}
			System.out.println(schema);
		} catch (final Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static void setSchema(final String cmd) {
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		try {
			String schema = cmd.substring(11).trim();
			if (schema.startsWith("\"")) {
				if (!schema.endsWith("\"")) {
					System.out.println("Unclosed quotes!");
					return;
				}

				schema = schema.substring(1, schema.length() - 1);
			}
			conn.setSchema(schema);
		} catch (final Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static void forceExternal(final String cmd) {
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		} else if (!cmd.toLowerCase().endsWith("on") && !cmd.toLowerCase().endsWith("off")) {
			System.out.println("force external command requires argument \"on\" or \"off\"");
			return;
		}

		boolean force = cmd.toLowerCase().endsWith("on");
		try {
			((XGConnection) conn).forceExternal(force);
		} catch (final Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static Pattern listTablesSyntax = Pattern.compile("list\\s+tables(?<verbose>\\s+verbose)?",
			Pattern.CASE_INSENSITIVE);

	private static Pattern listSystemTablesSyntax = Pattern.compile("list\\s+system\\s+tables(?<verbose>\\s+verbose)?",
			Pattern.CASE_INSENSITIVE);

	private static void listTables(final String cmd, boolean isSystemTables) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		ResultSet rs = null;

		try {
			final DatabaseMetaData dbmd = conn.getMetaData();
			final Matcher m = isSystemTables ? listSystemTablesSyntax.matcher(cmd) : listTablesSyntax.matcher(cmd);
			if (!m.matches()) {
				// this line will never be reached,
				// but we have to call matches() anyway
				return;
			}

			start = System.currentTimeMillis();
			if (isSystemTables) {
				final XGDatabaseMetaData xgdbmd = (XGDatabaseMetaData) dbmd;
				rs = xgdbmd.getSystemTables("", "%", "%", new String[0]);
			} else {
				rs = dbmd.getTables("", "%", "%", new String[0]);
			}
			final ResultSetMetaData meta = rs.getMetaData();

			if (m.group("verbose") != null) {
				printResultSet(rs, meta);
			} else {
				ArrayList<String> tableNames = new ArrayList<>();
				while (rs.next()) {
					tableNames.add(rs.getString("TABLE_SCHEM") + "." + rs.getString("TABLE_NAME"));
				}
				if (!tableNames.isEmpty()) {
					// TODO: This is a lexicographic sort. Clients ordering their tables by number
					// will not see the ordering they expect.
					Collections.sort(tableNames);
					for (String tableName : tableNames) {
						System.out.println(tableName);
					}
				}
			}
			printWarnings(rs);
			end = System.currentTimeMillis();

			rs.close();

			printTime(start, end);
		} catch (final Exception e) {
			try {
				rs.close();
			} catch (Exception f) {
			}

			System.out.println("Error: " + e.getMessage());
		}
	}

        private static void exportTranslation(final String cmd) {
                long start = 0;
                long end = 0;
                if (!isConnected()) {
                        System.out.println("No database connection exists");
                        return;
                }
                try {
                        final Statement stmt = conn.createStatement();
                        start = System.currentTimeMillis();
                        System.out.println(((XGStatement) stmt).exportTranslation(cmd));
                        printWarnings(stmt);
                        end = System.currentTimeMillis();

                        stmt.close();

                        printTime(start, end);
                } catch (final Exception e) {
                        System.out.println("Error: " + e.getMessage());
                }
        }

	private static void exportTable(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}
		try {
			final Statement stmt = conn.createStatement();
			start = System.currentTimeMillis();
			System.out.println(((XGStatement) stmt).exportTable(cmd));
			printWarnings(stmt);
			end = System.currentTimeMillis();

			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static Pattern listViewsSyntax = Pattern.compile("list\\s+views(?<verbose>\\s+verbose)?",
			Pattern.CASE_INSENSITIVE);

	private static void listViews(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		ResultSet rs = null;

		try {
			final DatabaseMetaData md = conn.getMetaData();
			final XGDatabaseMetaData dbmd = (XGDatabaseMetaData) md;
			final Matcher m = listViewsSyntax.matcher(cmd);
			if (!m.matches()) {
				// this line will never be reached,
				// but we have to call matches() anyway
				return;
			}

			start = System.currentTimeMillis();
			rs = dbmd.getViews("", "%", "%", new String[0]);
			final ResultSetMetaData meta = rs.getMetaData();

			if (m.group("verbose") != null) {
				printResultSet(rs, meta);
			} else {
				ArrayList<String> viewNames = new ArrayList<>();
				while (rs.next()) {
					viewNames.add(rs.getString("VIEW_SCHEM") + "." + rs.getString("VIEW_NAME"));
				}
				if (!viewNames.isEmpty()) {
					// TODO: This is a lexicographic sort. Clients ordering their tables by number
					// will not see the ordering they expect.
					Collections.sort(viewNames);
					for (String viewName : viewNames) {
						System.out.println(viewName);
					}
				}
			}
			printWarnings(rs);
			end = System.currentTimeMillis();

			rs.close();

			printTime(start, end);
		} catch (final Exception e) {
			try {
				rs.close();
			} catch (Exception f) {
			}

			System.out.println("Error: " + e.getMessage());
		}
	}

	private static Pattern describeTableSyntax = Pattern.compile(
			"describe(\\s+table\\s+)?((" + tk("schema") + ")\\.)?(" + tk("table") + ")(?<verbose>\\s+verbose)?",
			Pattern.CASE_INSENSITIVE);

	private static void describeTable(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		ResultSet rs = null;

		try {
			final Matcher m = describeTableSyntax.matcher(cmd);
			if (!m.matches()) {
				System.out.println("Syntax: describe (<schema>.)?<table>");
				return;
			}

			start = System.currentTimeMillis();
			final DatabaseMetaData dbmd = conn.getMetaData();
			rs = dbmd.getColumns("", getTk(m, "schema", conn.getSchema()), getTk(m, "table", null), "%");
			final ResultSetMetaData meta = rs.getMetaData();

			if (m.group("verbose") != null) {
				printResultSet(rs, meta);
			} else {
				final StringBuilder line = new StringBuilder(1024);
				while (rs.next()) {
					line.append(rs.getString("COLUMN_NAME"));
					line.append(" (");
					String type = rs.getString("TYPE_NAME");
					if (type.equals("SHORT")) {
						type = "SMALLINT";
					} else if (type.equals("LONG")) {
						type = "BIGINT";
					}

					line.append(type);
					// TODO: figure out precision stuff
					if (rs.getString("TYPE_NAME").equals("DECIMAL")) {
						line.append("(");
						line.append(rs.getString("DECIMAL_PRECISION"));
						line.append(",");
						line.append(rs.getString("DECIMAL_SCALE"));
						line.append(")");
					} else if (rs.getString("TYPE_NAME").equals("VARCHAR") || rs.getString("TYPE_NAME").equals("BINARY")
							|| rs.getString("TYPE_NAME").equals("HASH")) {
						line.append("(");
						line.append(rs.getString("COLUMN_SIZE"));
						line.append(")");
					}

					line.append(")");
					if (rs.getInt("NULLABLE") != 0) {
						line.append(" nullable");
					}
					line.append("\n");
				}
				if (line.length() != 0) {
					System.out.print(line);
				}
			}
			printWarnings(rs);
			end = System.currentTimeMillis();

			rs.close();

			printTime(start, end);
		} catch (final Exception e) {
			try {
				rs.close();
			} catch (Exception f) {
			}

			System.out.println("Error: " + e.getMessage());
		}
	}

	private static Pattern describeViewSyntax = Pattern.compile(
			"describe(\\s+view\\s+)?((" + tk("schema") + ")\\.)?(" + tk("view") + ")(?<verbose>\\s+verbose)?",
			Pattern.CASE_INSENSITIVE);

	private static void describeView(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		ResultSet rs = null;

		try {
			final Matcher m = describeViewSyntax.matcher(cmd);
			if (!m.matches()) {
				System.out.println("Syntax: describe view (<schema>.)?<view>");
				return;
			}

			start = System.currentTimeMillis();
			final DatabaseMetaData md = conn.getMetaData();
			final XGDatabaseMetaData dbmd = (XGDatabaseMetaData) md;
			rs = dbmd.getViews("", getTk(m, "schema", conn.getSchema()), getTk(m, "view", null), null);
			final ResultSetMetaData meta = rs.getMetaData();

			if (m.group("verbose") != null)
				printResultSet(rs, meta);

			else {
				final StringBuilder line = new StringBuilder(1024);

				while (rs.next())
					line.append(rs.getString("VIEW_QUERY_TEXT"));

				if (line.length() != 0) {
					line.setLength(line.length() - 2);
					System.out.println(line);
				}
			}
			printWarnings(rs);
			end = System.currentTimeMillis();
			rs.close();

			printTime(start, end);
		} catch (final Exception e) {
			try {
				rs.close();
			} catch (Exception f) {
			}

			System.out.println("Error: " + e.getMessage());
		}
	}

	private static Pattern listIndexesSyntax = Pattern.compile(
			"list\\s+ind(ic|ex)es\\s+((" + tk("schema") + ")\\.)?(" + tk("table") + ")(?<verbose>\\s+verbose)?",
			Pattern.CASE_INSENSITIVE);

	private static void listIndexes(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		ResultSet rs = null;

		try {
			final Matcher m = listIndexesSyntax.matcher(cmd);
			if (!m.matches()) {
				System.out.println("Syntax: list indexes (<schema>.)?<table>");
				return;
			}

			start = System.currentTimeMillis();
			final DatabaseMetaData dbmd = conn.getMetaData();
			// this behavior is slightly different from the jdbc call itself--
			// the call allows schema to be null, in which case it doesn't filter on it.
			// we assume the current schema for convenience & to limit results to one table
			rs = dbmd.getIndexInfo("", getTk(m, "schema", conn.getSchema()), getTk(m, "table", null), false, false);
			final ResultSetMetaData meta = rs.getMetaData();

			if (m.group("verbose") != null) {
				printResultSet(rs, meta);
			} else {
				final StringBuilder line = new StringBuilder(1024);
				ArrayList<String> indexNames = new ArrayList<>();
				String currIndex = "";
				while (rs.next()) {
					final String nextIndex = rs.getString("INDEX_NAME");
					if (!nextIndex.equals(currIndex)) {
						currIndex = nextIndex;
						if (line.length() > 0) {
							line.setLength(line.length() - 2);
							line.append(")");
							indexNames.add(line.toString());
							line.setLength(0);
						}
						line.append(currIndex);
						line.append(" (");
					}
					line.append(rs.getString("COLUMN_NAME"));
					line.append(", ");
				}
				if (line.length() != 0) {
					line.setLength(line.length() - 2);
					line.append(")");
					indexNames.add(line.toString());
				}
				if (!indexNames.isEmpty()) {
					// TODO: This is a lexicographic sort. Clients ordering their tables by number
					// will not see the ordering they expect.
					Collections.sort(indexNames);
					for (String indexName : indexNames) {
						System.out.println(indexName);
					}
				}
			}

			printWarnings(rs);
			end = System.currentTimeMillis();
			rs.close();

			printTime(start, end);
		} catch (final Exception e) {
			try {
				rs.close();
			} catch (Exception f) {
			}

			System.out.println("Error: " + e.getMessage());
		}
	}

	private static void select(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		Statement stmt = null;
		ResultSet rs = null;

		try {
			stmt = conn.createStatement();
			start = System.currentTimeMillis();
			rs = stmt.executeQuery(cmd);
			printWarnings(stmt);
			final ResultSetMetaData meta = rs.getMetaData();

			if (outputCSVFile.isEmpty()) {
				printResultSet(rs, meta);
			} else {
				outputResultSet(rs, meta);
				outputCSVFile = "";
			}
			printWarnings(rs);
			end = System.currentTimeMillis();

			rs.close();
			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			try {
				rs.close();
				stmt.close();
			} catch (Exception f) {
			}
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static void explain(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}
		Statement stmt = null;
		ResultSet rs = null;

		try {
			stmt = conn.createStatement();
			start = System.currentTimeMillis();
			rs = stmt.executeQuery(cmd);

			while (rs.next()) {
				String planLine = rs.getString(1);
				System.out.println(planLine);
			}
			end = System.currentTimeMillis();

			rs.close();
			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			try {
				rs.close();
				stmt.close();
			} catch (Exception f) {
			}
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static void executePlan(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		Statement stmt = null;
		ResultSet rs = null;
		String plan = cmd.substring("PLAN EXECUTE ".length()).trim();

		if (startsWithIgnoreCase(plan, "INLINE ")) {
			plan = plan.substring("INLINE ".length()).trim();

			try {
				stmt = conn.createStatement();
				start = System.currentTimeMillis();
				rs = ((XGStatement) stmt).executeInlinePlan(plan);
				final ResultSetMetaData meta = rs.getMetaData();

				printResultSet(rs, meta);
				printWarnings(stmt);
				printWarnings(rs);
				end = System.currentTimeMillis();

				rs.close();
				stmt.close();

				printTime(start, end);
			} catch (final Exception e) {
				try {
					rs.close();
					stmt.close();
				} catch (Exception f) {
				}
				System.out.println("Error: " + e.getMessage());
			}
		} else {
			try {
				stmt = conn.createStatement();
				start = System.currentTimeMillis();
				rs = ((XGStatement) stmt).executePlan(plan);
				final ResultSetMetaData meta = rs.getMetaData();

				printResultSet(rs, meta);
				printWarnings(stmt);
				printWarnings(rs);
				end = System.currentTimeMillis();

				rs.close();
				stmt.close();

				printTime(start, end);
			} catch (final Exception e) {
				try {
					rs.close();
					stmt.close();
				} catch (Exception f) {
				}
				System.out.println("Error: " + e.getMessage());
			}
		}
	}

	private static void explainPlan(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		try {
			final Statement stmt = conn.createStatement();
			start = System.currentTimeMillis();
			String plan = cmd.substring("PLAN EXPLAIN ".length()).trim();

			boolean jsonFormat = false;
			if (plan.substring(0, "JSON ".length()).equalsIgnoreCase("JSON ")) {
				plan = plan.substring("JSON ".length()).trim();
				jsonFormat = true;
			}
			final PlanMessage pm = ((XGStatement) stmt).explainPlan(plan);

			if (jsonFormat) {
				final String planJsonFormat = JsonFormat.printer().print(pm);
				System.out.println(planJsonFormat);
			} else {
				System.out.println(pm);
			}
			printWarnings(stmt);
			end = System.currentTimeMillis();
			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static void listPlan() {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		try {
			final Statement stmt = conn.createStatement();
			start = System.currentTimeMillis();
			final ArrayList<String> planNames = ((XGStatement) stmt).listPlan();
			if (planNames.size() > 0) {
				System.out.println("    Plan Name    ");
				System.out.println("-----------------");
				for (int i = 0; i < planNames.size(); ++i)
					System.out.println(planNames.get(i));
			}

			printWarnings(stmt);
			end = System.currentTimeMillis();

			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("CLI Error: " + e.getMessage());
		}
	}

	private static void cancelQuery(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}
		try {
			final Statement stmt = conn.createStatement();
			start = System.currentTimeMillis();
			final String uuid = cmd.substring("CANCEL ".length()).trim();
			((XGStatement) stmt).cancelQuery(uuid);
			printWarnings(stmt);
			end = System.currentTimeMillis();

			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("CLI Error: " + e.getMessage());
		}
	}

	private static void setQueryTimeout(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}
		try {
			start = System.currentTimeMillis();
			final int timeout = Integer.parseInt(cmd.substring("SET TIMEOUT ".length()).trim());
			((XGConnection) conn).setTimeout(timeout);
			end = System.currentTimeMillis();
			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("CLI Error: " + e.getMessage());
		}
	}

	private static void killQuery(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}
		try {
			final Statement stmt = conn.createStatement();
			start = System.currentTimeMillis();
			final String uuid = cmd.substring("KILL ".length()).trim();
			((XGStatement) stmt).killQuery(uuid);
			printWarnings(stmt);
			end = System.currentTimeMillis();

			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("CLI Error: " + e.getMessage());
		}
	}

	private static void listAllQueries() {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}
		try {
			final Statement stmt = conn.createStatement();
			start = System.currentTimeMillis();
			final ArrayList<SysQueriesRow> queryList = ((XGStatement) stmt).listAllQueries();

			if (queryList.size() > 0) {
				printAllQueries(queryList);
			}

			printWarnings(stmt);
			end = System.currentTimeMillis();

			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("CLI Error: " + e.getMessage());
		}
	}

	private static void outputNextQuery(final String cmd) {
		try {
			outputCSVFile = cmd.substring("OUTPUT NEXT QUERY ".length()).trim();
			if (outputCSVFile.isEmpty()) {
				System.out.println("Provide a filename to output the query to");
			}
		} catch (final Exception e) {
			System.out.println("CLI Error: " + e.getMessage());
		}
	}

	private static void printAllQueries(final ArrayList<SysQueriesRow> queries) {
		System.out.format("%-40s%-15s%-15s%-20s%-20s%-15s%-20s%-15s%s\n", "query id", "user", "importance",
				"estimated time", "elapsed time", "status", "server", "database", "sql");
		System.out.println(new String(new char[170]).replace("\0", "-"));
		for (SysQueriesRow row : queries) {
			System.out.format("%-40s%-15s%-15s%-20s%-20s%-15s%-20s%-15s%s\n", row.getQueryId(), row.getUserid(),
					row.getImportance(), row.getEstimatedTimeSec(), row.getElapsedTimeSec(), row.getStatus(),
					row.getQueryServer(), row.getDatabase(), row.getSqlText());
		}
	}

	private static void update(final String cmd) {
		long start = 0;
		long end = 0;
		if (!isConnected()) {
			System.out.println("No database connection exists");
			return;
		}

		try {
			final Statement stmt = conn.createStatement();
			start = System.currentTimeMillis();
			final long numRows = stmt.executeUpdate(cmd);
			end = System.currentTimeMillis();

			System.out.println("Modified " + numRows + (numRows == 1 ? " row" : " rows"));
			printWarnings(stmt);

			stmt.close();

			printTime(start, end);
		} catch (final Exception e) {
			System.out.println("Error: " + e.getMessage());
		}
	}

	private static boolean sourceCommands(BufferedReader reader) throws IOException {
		boolean quit = false;

		try {
			while (true) {
				String line = reader.readLine();
				if (line == null)
					return quit;

				String cmd = scrubCommand(line + " ");

				while (true) {
					quit = cmd.trim().equalsIgnoreCase("QUIT");
					if (quit) {
						try {
							if (conn != null && !conn.isClosed())
								conn.close();
						} catch (final Exception e) {
						}
						return quit;
					}

					if (!comment && quote == '\0' && cmd.trim().endsWith(";")) {
						cmd = cmd.trim();
						cmd = cmd.substring(0, cmd.length() - 1).trim();
						if (trace && !endsWithIgnoreCase(cmd, " trace"))
							cmd = cmd + " trace";
						break;
					} else {
						String cont = reader.readLine();
						if (cont == null)
							return quit;

						cmd += scrubCommand(line + " ");
					}
				}

				quit = processCommand(cmd);
				if (quit)
					return quit;
			}
		} catch (final UserInterruptException | EndOfFileException e) {
		}

		return quit;

	}

	private static boolean source(final String cmd) {

		boolean quit = false;

		String[] tokens = cmd.split("\\s+");
		if (1 == tokens.length) {
			System.out.println(tokens[0] + " error: filename missing");
			return quit;
		}

		if (3 < tokens.length || 3 == tokens.length && !tokens[2].equalsIgnoreCase("TRACE")) {
			System.out.println("Error: too many parameters: " + cmd);
			return quit;
		}

		File file = new File(tokens[1]);
		boolean added = sources.add(file);
		if (!added) {
			System.out.println(tokens[0] + " error: " + file + " (source file already open)");
			return quit;
		}

		long start = System.currentTimeMillis();

		try {
			Reader reader = new FileReader(file);
			BufferedReader bufferedReader = new BufferedReader(reader);

			char oldQuote = quote;
			quote = '\0';

			boolean oldComment = comment;
			comment = false;

			if (3 == tokens.length)
				System.out.println(tokens[0] + ": Sourcing " + tokens[1]);

			try {
				quit = sourceCommands(bufferedReader);
			} catch (Throwable e) {
			}

			if (3 == tokens.length)
				System.out.println(tokens[0] + ": Closing " + tokens[1]);

			comment = oldComment;
			quote = oldQuote;

			bufferedReader.close();
			reader.close();
		} catch (Throwable e) {
			System.out.println(tokens[0] + " error: " + e.getMessage());
		}

		printTime(start, System.currentTimeMillis());

		boolean removed = sources.remove(file);

		return quit;
	}

	private final static char[] hexArray = "0123456789abcdef".toCharArray();

	private static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}

	private static void printResultSet(final ResultSet rs, final ResultSetMetaData meta) throws Exception {
		if (!performance) {
			final ArrayList<Integer> offsets = new ArrayList<>();
			int i = 1;
			final StringBuilder line = new StringBuilder(64 * 1024);
			final int colCount = meta.getColumnCount();
			while (i <= colCount) {
				offsets.add(line.length());
				final int nameWidth = meta.getColumnName(i).length();
				final int valsWidth = meta.getColumnDisplaySize(i);
				line.append((meta.getColumnName(i)));
				int j = 0;
				while (j < Math.max((valsWidth - nameWidth), 1)) {
					line.append(" ");
					j++;
				}

				i++;
			}

			System.out.println(line);
			final int len = line.length();
			line.setLength(0);
			i = 0;
			while (i < len) {
				line.append('-');
				i++;
			}

			System.out.println(line);
			line.setLength(0);
			long rowCount = 0;
			while (rs.next()) {
				rowCount++;
				i = 1;
				final int s = line.length();
				while (i <= colCount) {
					final int target = s + offsets.get(i - 1);
					final int x = target - line.length();
					int y = 0;
					while (y < x) {
						line.append(" ");
						y++;
					}
					Object o = (rs.getObject(i));
					if (rs.wasNull()) {
						o = "NULL";
					} else if (o instanceof byte[]) {
						o = "0x" + bytesToHex((byte[]) o);
					}
					line.append(o);
					line.append(" ");
					i++;
				}

				if (line.length() >= 32 * 1024) {
					System.out.println(line);
					line.setLength(0);
				} else {
					line.append("\n");
				}
			}

			if (line.length() != 0) {
				System.out.println(line);
			}
			System.out.println("Fetched " + rowCount + (rowCount == 1 ? " row" : " rows"));
		} else {
			long rowCount = 0;
			while (rs.next()) {
				rowCount++;
			}

			System.out.println("Fetched " + rowCount + (rowCount == 1 ? " row" : " rows"));
		}
	}

	private static void outputResultSet(final ResultSet rs, final ResultSetMetaData meta) throws Exception {
		FileOutputStream out = new FileOutputStream(outputCSVFile);
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			String colType = meta.getColumnTypeName(i);
			if (colType != null) {
				out.write(colType.getBytes());
			}
			if (i < meta.getColumnCount())
				out.write(',');
		}
		out.write('\n');
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			String colType = meta.getColumnLabel(i);
			if (colType != null) {
				out.write(colType.getBytes());
			}
			if (i < meta.getColumnCount())
				out.write(',');
		}
		out.write('\n');
		int rowCount = 0;
		while (rs.next()) {
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				out.write('"');
				Object o = rs.getObject(i);
				String valueString = "NULL";
				if (rs.wasNull()) {
					valueString = "NULL";
				} else if (o instanceof byte[]) {
					valueString = "0x" + bytesToHex((byte[]) o);
				} else if (o != null) {
					valueString = o.toString();
				}
				for (int j = 0; j < valueString.length(); j++) {
					if (valueString.charAt(j) == '"') {
						out.write("\"\"".getBytes());
					} else {
						out.write(valueString.charAt(j));
					}
				}
				out.write('"');
				if (i < meta.getColumnCount())
					out.write(',');
			}
			out.write('\n');
			rowCount++;
		}
		out.close();
		System.out.println("Fetched " + rowCount + (rowCount == 1 ? " row" : " rows"));
	}

	private static void printWarnings(final ResultSet rs) throws SQLException {
		SQLWarning warn = rs.getWarnings();
		while (warn != null) {
			System.out.println("Warning: " + warn.getMessage());
			warn = warn.getNextWarning();
		}
	}

	private static void printWarnings(final Statement st) throws SQLException {
		SQLWarning warn = st.getWarnings();
		while (warn != null) {
			System.out.println("Warning: " + warn.getMessage());
			warn = warn.getNextWarning();
		}
	}

	private static void printTime(long start, long end) {
		if (timing) {
			System.out.println("\nCommand took " + (end - start) / 1000.0 + " seconds");
		}
	}
}
