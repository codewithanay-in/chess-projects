import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChessMoveExtractor {
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String targetUsername,year,month,timeControlFilter;
        if (args.length >=4) {
            targetUsername=args[0];
            year=args[1];
            month=args[2];
            timeControlFilter=args[3];
        }
        else{
            // Step 1: Get user input
            System.out.print("Enter Chess.com username: ");
            targetUsername = scanner.nextLine().trim();
            System.out.print("Enter year : ");
            year = scanner.nextLine().trim();
            System.out.print("Enter month (0 for entire year): ");
            month = scanner.nextLine().trim();
            System.out.print("Enter time control filter (e.g., 600, 180+2, or 0 for all games): ");
            timeControlFilter = scanner.nextLine().trim();
        }
        // Step 2: Construct the API URL based on whether month is 0 or not
        String baseUrl;
        boolean isAnnual = month.equals("0");
        
        if (isAnnual) {
            baseUrl = String.format("https://api.chess.com/pub/player/%s/games/%s", targetUsername, year);
            System.out.println("Fetching ALL games for year " + year + " from: " + baseUrl);
        } else {
            baseUrl = String.format("https://api.chess.com/pub/player/%s/games/%s/%s", targetUsername, year, month);
            System.out.println("Fetching data from: " + baseUrl);
        }

        try {
            // Step 3: Fetch the data from Chess.com
            String pgnData;
            if (isAnnual) {
                // Annual endpoint returns JSON with monthly archive URLs
                pgnData = fetchAndParseAnnualGames(baseUrl, targetUsername);
            } else {
                // Monthly endpoint returns PGN directly
                String pgnUrl = baseUrl + "/pgn";
                pgnData = fetchDataFromUrl(pgnUrl);
            }

            if (pgnData == null || pgnData.isEmpty()) {
                System.out.println("No games found or the user/month/year is invalid.");
                return;
            }

            // Step 4: Create the filename
            String shortYear = year.substring(2);
            String fileName;
            if (isAnnual) {
                fileName = targetUsername + "_" + shortYear + ".txt";
            } else {
                String formattedMonth = String.format("%02d", Integer.parseInt(month));
                fileName = targetUsername + "_" + shortYear + formattedMonth + ".txt";
            }
            System.out.println("Processing games...");

            // Step 5: Extract moves with metadata and save to file
            GameStats stats = extractAndSaveGames(pgnData, targetUsername, fileName, timeControlFilter);
            
            if (stats.getTotalGames() == 0) {
                System.out.println("No games match the specified time control filter.");
                return;
            }
            
            // Step 6: Append detailed statistics to the file
            appendDetailedStatisticsToFile(fileName, stats, targetUsername, year, month);
            
            System.out.println("\nSuccess! Games saved to: " + fileName);
            printConsoleSummary(stats);

        } catch (IOException | InterruptedException e) {
            System.err.println("Error: " + e.getMessage());
            System.out.println("\nPossible reasons:");
            System.out.println("1. The username might be incorrect");
            System.out.println("2. There are no games for the specified year/month");
            System.out.println("3. The year might be in the future (e.g., 2026)");
            System.out.println("4. Network connection issue");
        } finally {
            scanner.close();
        }
    }

    // Method to download PGN data from the web (monthly endpoint)
    private static String fetchDataFromUrl(String urlString) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlString))
                .header("User-Agent", "ChessMoveExtractor/3.0 (Java 25)")
                .header("Accept", "text/plain, application/json")
                .timeout(java.time.Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status == 404) {
            System.out.println("No games found at: " + urlString);
            return "";
        }
        if (status != 200) {
            throw new IOException("HTTP Error: " + status + " for URL: " + urlString);
        }

        return response.body();
    }

    // Method to fetch and parse annual games from JSON
    private static String fetchAndParseAnnualGames(String urlString, String username) throws IOException, InterruptedException {
        System.out.println("Fetching annual game archives...");
        StringBuilder allPgns = new StringBuilder();
        
        try {
            // First, fetch the JSON that contains monthly archive URLs
            String jsonResponse = fetchDataFromUrl(urlString);
            if (jsonResponse.isEmpty()) {
                return "";
            }
            
            // Parse the JSON to get monthly archive URLs
            List<String> monthlyUrls = extractMonthlyUrlsFromJson(jsonResponse);
            
            if (monthlyUrls.isEmpty()) {
                System.out.println("No monthly archives found.");
                return "";
            }
            
            System.out.println("Found " + monthlyUrls.size() + " monthly archive(s).");
            
            // Fetch PGNs from each monthly archive
            int archiveCount = 0;
            for (String monthlyUrl : monthlyUrls) {
                archiveCount++;
                System.out.println("Processing archive " + archiveCount + " of " + monthlyUrls.size() + "...");
                
                try {
                    String monthlyPgn = fetchDataFromUrl(monthlyUrl + "/pgn");
                    if (!monthlyPgn.isEmpty()) {
                        allPgns.append(monthlyPgn).append("\n");
                    }
                } catch (IOException | InterruptedException e) {
                    System.out.println("Skipping archive: " + monthlyUrl + " (Error: " + e.getMessage() + ")");
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error parsing annual data: " + e.getMessage());
            throw new IOException("Failed to fetch annual games: " + e.getMessage());
        }
        
        return allPgns.toString();
    }

    // Extract monthly archive URLs from JSON response
    private static List<String> extractMonthlyUrlsFromJson(String jsonResponse) {
        List<String> urls = new ArrayList<>();
        
        try {
            // The JSON format is: {"archives":["url1","url2",...]}
            // Simple parsing without external JSON library
            if (jsonResponse.contains("\"archives\":[")) {
                int start = jsonResponse.indexOf("\"archives\":[") + 12;
                int end = jsonResponse.indexOf("]", start);
                
                if (start > 12 && end > start) {
                    String archivesStr = jsonResponse.substring(start, end);
                    // Split by comma and clean up
                    String[] archiveArray = archivesStr.split(",");
                    
                    for (String archive : archiveArray) {
                        String url = archive.trim()
                            .replace("\"", "")
                            .replace("\\/", "/"); // Unescape slashes
                        if (!url.isEmpty() && url.startsWith("http")) {
                            urls.add(url);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
        }
        
        return urls;
    }

    // Class to hold game statistics
    static class GameStats {
        private int totalGames = 0;
        private int won = 0;
        private int lost = 0;
        private int draw = 0;
        
        // Color-specific statistics
        private int whiteGames = 0;
        private int whiteWon = 0;
        private int whiteLost = 0;
        private int whiteDraw = 0;
        
        private int blackGames = 0;
        private int blackWon = 0;
        private int blackLost = 0;
        private int blackDraw = 0;
        
        // Game type statistics
        private Map<String, GameTypeStats> gameTypeStats = new HashMap<>();
        
        // Rating tracking
        private Map<String, RatingTracker> ratingTrackers = new HashMap<>();
        
        // Move statistics
        private int totalMoves = 0;
        private List<Integer> moveCounts = new ArrayList<>();
        
        public void addGame(String gameType, String resultLabel, String color, int moveCount, int userRating, String date) {
            totalGames++;
            totalMoves += moveCount;
            moveCounts.add(moveCount);
            
            // Only track valid ratings (above 0)
            boolean hasValidRating = userRating > 0;
            
            // Update result statistics
            switch (resultLabel) {
                case "(won)": won++; break;
                case "(lost)": lost++; break;
                case "(draw)": draw++; break;
            }
            
            // Update color statistics
            if (color.equals("White")) {
                whiteGames++;
                switch (resultLabel) {
                    case "(won)": whiteWon++; break;
                    case "(lost)": whiteLost++; break;
                    case "(draw)": whiteDraw++; break;
                }
            } else {
                blackGames++;
                switch (resultLabel) {
                    case "(won)": blackWon++; break;
                    case "(lost)": blackLost++; break;
                    case "(draw)": blackDraw++; break;
                }
            }
            
            // Update game type statistics (only for games with valid ratings)
            if (hasValidRating) {
                GameTypeStats typeStats = gameTypeStats.getOrDefault(gameType, new GameTypeStats(gameType));
                typeStats.addGame(resultLabel, moveCount, userRating, date);
                gameTypeStats.put(gameType, typeStats);
                
                // Update rating tracker with date for chronological sorting
                RatingTracker tracker = ratingTrackers.getOrDefault(gameType, new RatingTracker());
                tracker.addRating(userRating, date);
                ratingTrackers.put(gameType, tracker);
            }
        }
        
        // Getters
        public int getTotalGames() { return totalGames; }
        public int getWon() { return won; }
        public int getLost() { return lost; }
        public int getDraw() { return draw; }
        
        public int getWhiteGames() { return whiteGames; }
        public int getWhiteWon() { return whiteWon; }
        public int getWhiteLost() { return whiteLost; }
        public int getWhiteDraw() { return whiteDraw; }
        
        public int getBlackGames() { return blackGames; }
        public int getBlackWon() { return blackWon; }
        public int getBlackLost() { return blackLost; }
        public int getBlackDraw() { return blackDraw; }
        
        public double getAverageMoves() { 
            return totalGames > 0 ? (double) totalMoves / totalGames : 0; 
        }
        
        public Map<String, GameTypeStats> getGameTypeStats() { return gameTypeStats; }
        public Map<String, RatingTracker> getRatingTrackers() { return ratingTrackers; }
        public List<Integer> getMoveCounts() { return moveCounts; }
    }
    
    // Modified RatingTracker class with chronological tracking
    static class RatingTracker {
        private List<RatingEntry> ratings = new ArrayList<>();
        
        static class RatingEntry {
            int rating;
            String date; // Format: "YYYY.MM.DD"
            
            RatingEntry(int rating, String date) {
                this.rating = rating;
                this.date = date;
            }
        }
        
        public void addRating(int rating, String date) {
            if (rating > 0 && date != null && !date.equals("?")) {
                ratings.add(new RatingEntry(rating, date));
            }
        }
        
        public int getRatingChange() {
            if (ratings.size() < 2) return 0;
            
            // Sort by date chronologically
            sortRatingsByDate();
            
            return ratings.get(ratings.size() - 1).rating - ratings.get(0).rating;
        }
        
        public int getLatestRating() {
            if (ratings.isEmpty()) return 0;
            
            // Sort by date and get most recent
            sortRatingsByDate();
            return ratings.get(ratings.size() - 1).rating;
        }
        
        public int getStartingRating() {
            if (ratings.isEmpty()) return 0;
            
            // Sort by date and get earliest
            sortRatingsByDate();
            return ratings.get(0).rating;
        }
        
        public int getHighestRating() {
            return ratings.stream()
                .mapToInt(r -> r.rating)
                .max()
                .orElse(0);
        }
        
        public int getLowestRating() {
            return ratings.stream()
                .filter(r -> r.rating > 0)
                .mapToInt(r -> r.rating)
                .min()
                .orElse(0);
        }
        
        public int getAverageRating() {
            if (ratings.isEmpty()) return 0;
            return (int) ratings.stream()
                .mapToInt(r -> r.rating)
                .average()
                .orElse(0);
        }
        
        private void sortRatingsByDate() {
            ratings.sort((r1, r2) -> {
                try {
                    // Parse dates in format YYYY.MM.DD
                    String[] parts1 = r1.date.split("\\.");
                    String[] parts2 = r2.date.split("\\.");
                    
                    int year1 = Integer.parseInt(parts1[0]);
                    int month1 = Integer.parseInt(parts1[1]);
                    int day1 = Integer.parseInt(parts1[2]);
                    
                    int year2 = Integer.parseInt(parts2[0]);
                    int month2 = Integer.parseInt(parts2[1]);
                    int day2 = Integer.parseInt(parts2[2]);
                    
                    if (year1 != year2) return Integer.compare(year1, year2);
                    if (month1 != month2) return Integer.compare(month1, month2);
                    return Integer.compare(day1, day2);
                } catch (Exception e) {
                    return 0;
                }
            });
        }
    }
    
    // Modified GameTypeStats class
    static class GameTypeStats {
        private String gameType;
        private int total = 0;
        private int won = 0;
        private int lost = 0;
        private int draw = 0;
        private int totalMoves = 0;
        private int minRating = Integer.MAX_VALUE;
        private int maxRating = 0;
        private int latestRating = 0;
        private List<Integer> ratings = new ArrayList<>();
        private List<String> dates = new ArrayList<>();
        
        public GameTypeStats(String gameType) {
            this.gameType = gameType;
        }
        
        public void addGame(String resultLabel, int moveCount, int rating, String date) {
            if (rating <= 0) return; // Skip invalid ratings
            
            total++;
            totalMoves += moveCount;
            ratings.add(rating);
            if (date != null && !date.equals("?")) {
                dates.add(date);
            }
            
            // Update min/max ratings
            if (rating < minRating) minRating = rating;
            if (rating > maxRating) maxRating = rating;
            
            // Update latest rating based on date
            updateLatestRating(rating, date);
            
            switch (resultLabel) {
                case "(won)": won++; break;
                case "(lost)": lost++; break;
                case "(draw)": draw++; break;
            }
        }
        
        private void updateLatestRating(int rating, String date) {
            if (date == null || date.equals("?")) return;
            
            if (dates.isEmpty()) {
                latestRating = rating;
            } else {
                try {
                    // Find the index of this date
                    int currentIndex = dates.size() - 1;
                    String currentDate = dates.get(currentIndex);
                    
                    if (isDateLater(date, currentDate)) {
                        latestRating = rating;
                    } else if (latestRating == 0) {
                        latestRating = rating;
                    }
                } catch (Exception e) {
                    // If date parsing fails, update anyway if no latest rating
                    if (latestRating == 0) {
                        latestRating = rating;
                    }
                }
            }
        }
        
        private boolean isDateLater(String date1, String date2) {
            try {
                String[] parts1 = date1.split("\\.");
                String[] parts2 = date2.split("\\.");
                
                int year1 = Integer.parseInt(parts1[0]);
                int month1 = Integer.parseInt(parts1[1]);
                int day1 = Integer.parseInt(parts1[2]);
                
                int year2 = Integer.parseInt(parts2[0]);
                int month2 = Integer.parseInt(parts2[1]);
                int day2 = Integer.parseInt(parts2[2]);
                
                if (year1 > year2) return true;
                if (year1 == year2 && month1 > month2) return true;
                if (year1 == year2 && month1 == month2 && day1 > day2) return true;
                return false;
            } catch (Exception e) {
                return false;
            }
        }
        
        public double getWinRate() {
            return total > 0 ? (double) won / total * 100 : 0;
        }
        
        public double getLossRate() {
            return total > 0 ? (double) lost / total * 100 : 0;
        }
        
        public double getDrawRate() {
            return total > 0 ? (double) draw / total * 100 : 0;
        }
        
        public double getAverageMoves() {
            return total > 0 ? (double) totalMoves / total : 0;
        }
        
        public int getRatingRange() {
            if (minRating == Integer.MAX_VALUE || maxRating == 0) return 0;
            return maxRating - minRating;
        }
        
        public int getAverageRating() {
            if (ratings.isEmpty()) return 0;
            return (int) ratings.stream().mapToInt(Integer::intValue).average().orElse(0);
        }
        
        // Getters
        public String getGameType() { return gameType; }
        public int getTotal() { return total; }
        public int getWon() { return won; }
        public int getLost() { return lost; }
        public int getDraw() { return draw; }
        public int getMinRating() { return minRating == Integer.MAX_VALUE ? 0 : minRating; }
        public int getMaxRating() { return maxRating; }
        public int getLatestRating() { return latestRating; }
    }

    // Main method to process all games in the PGN data
    private static GameStats extractAndSaveGames(String pgnData, String targetUsername, String fileName, String timeControlFilter) throws IOException {
        // Split the large PGN into individual game blocks
        String[] rawGameBlocks = pgnData.split("\\n\\s*\\n(?=\\[)");
        GameStats stats = new GameStats();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            int gameCount = 0;
            int filteredCount = 0;

            for (String gameBlock : rawGameBlocks) {
                gameBlock = gameBlock.trim();
                if (gameBlock.isEmpty()) continue;

                gameCount++;
                System.out.print("Processing Game " + gameCount + "... ");

                // Parse this single game
                GameData gameData = parseSingleGame(gameBlock, targetUsername);
                
                // Apply time control filter
                if (!timeControlFilter.equals("0") && !matchesTimeControl(gameData.getTimeControlRaw(), timeControlFilter)) {
                    System.out.println("Skipped (time control filter)");
                    continue;
                }
                
                filteredCount++;
                System.out.println("Added");
                
                // Calculate move count
                int moveCount = calculateMoveCount(gameData.getMoves());
                
                // Determine user's color
                String userColor = determineUserColor(targetUsername, gameData.getWhitePlayer(), gameData.getBlackPlayer());
                
                // Get user's rating
                int userRating = getUserRating(targetUsername, gameData.getWhitePlayer(), gameData.getBlackPlayer(), 
                                             gameData.getWhiteElo(), gameData.getBlackElo());
                
                // Update statistics
                stats.addGame(gameData.getGameType(), gameData.getResultLabel(), userColor, moveCount, userRating, gameData.getDate());

                // Write the formatted output for this game
                writer.write("--- Game " + filteredCount + " " + gameData.getResultLabel() + " " + 
                           gameData.getFormattedTimeControl() + " (" + gameData.getGameType() + ") ---\n");
                writer.write("Color: " + userColor + " | Rating: " + (userRating > 0 ? userRating : "?") + 
                           " | Date: " + gameData.getDate() + "\n");
                writer.write(gameData.getMoves());
                writer.write("\n\n");
            }
            System.out.println("\nFinished processing " + filteredCount + " of " + gameCount + " game(s).");
        }
        return stats;
    }

    // Check if game time control matches filter
    private static boolean matchesTimeControl(String gameTimeControl, String filter) {
        if (gameTimeControl == null || gameTimeControl.equals("?") || gameTimeControl.equals("-")) {
            return false;
        }
        
        // Normalize both to compare
        String normalizedGame = gameTimeControl.trim();
        String normalizedFilter = filter.trim();
        
        // Exact match
        if (normalizedGame.equals(normalizedFilter)) {
            return true;
        }
        
        // If filter is just base time (e.g., "600"), check if game starts with it
        if (!filter.contains("+") && normalizedGame.startsWith(normalizedFilter)) {
            return true;
        }
        
        return false;
    }

    // Class to hold parsed data for a single game
    static class GameData {
        private String moves;
        private String resultLabel;
        private String formattedTimeControl;
        private String gameType;
        private String timeControlRaw;
        private String whitePlayer;
        private String blackPlayer;
        private String whiteElo;
        private String blackElo;
        private String date;

        public GameData(String moves, String resultLabel, String formattedTimeControl, 
                       String gameType, String timeControlRaw, String whitePlayer, 
                       String blackPlayer, String whiteElo, String blackElo, String date) {
            this.moves = moves;
            this.resultLabel = resultLabel;
            this.formattedTimeControl = formattedTimeControl;
            this.gameType = gameType;
            this.timeControlRaw = timeControlRaw;
            this.whitePlayer = whitePlayer;
            this.blackPlayer = blackPlayer;
            this.whiteElo = whiteElo;
            this.blackElo = blackElo;
            this.date = date;
        }

        public String getMoves() { return moves; }
        public String getResultLabel() { return resultLabel; }
        public String getFormattedTimeControl() { return formattedTimeControl; }
        public String getGameType() { return gameType; }
        public String getTimeControlRaw() { return timeControlRaw; }
        public String getWhitePlayer() { return whitePlayer; }
        public String getBlackPlayer() { return blackPlayer; }
        public String getWhiteElo() { return whiteElo; }
        public String getBlackElo() { return blackElo; }
        public String getDate() { return date; }
    }

    // Parse headers and moves for a single game block
    private static GameData parseSingleGame(String gameBlock, String targetUsername) {
        // Separate headers from the move text
        String[] lines = gameBlock.split("\\n");
        StringBuilder headerSection = new StringBuilder();
        StringBuilder moveSection = new StringBuilder();

        boolean inMoveSection = false;
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("[")) {
                if (!inMoveSection) {
                    headerSection.append(line).append("\n");
                }
            } else {
                inMoveSection = true;
                if (!line.isEmpty()) {
                    moveSection.append(line).append(" ");
                }
            }
        }

        String headers = headerSection.toString();
        String moves = cleanMoves(moveSection.toString().trim());

        // Extract key metadata from headers
        String whitePlayer = extractHeader(headers, "White");
        String blackPlayer = extractHeader(headers, "Black");
        String result = extractHeader(headers, "Result");
        String timeControlRaw = extractHeader(headers, "TimeControl");
        String eventType = extractHeader(headers, "Event");
        String whiteElo = extractHeader(headers, "WhiteElo");
        String blackElo = extractHeader(headers, "BlackElo");
        
        // Extract date from headers
        String date = extractHeader(headers, "UTCDate");
        if (date.equals("?")) {
            date = extractHeader(headers, "Date");
        }

        // Determine the result for the target user
        String userResultLabel = determineUserResult(targetUsername, whitePlayer, blackPlayer, result);

        // Format the time control
        String formattedTimeControl = formatTimeControl(timeControlRaw);

        // Determine game type
        String gameType = determineGameType(timeControlRaw, eventType);

        return new GameData(moves, userResultLabel, formattedTimeControl, gameType, 
                           timeControlRaw, whitePlayer, blackPlayer, whiteElo, blackElo, date);
    }

    // Extract the value from a PGN header line
    private static String extractHeader(String headers, String key) {
        Pattern pattern = Pattern.compile("\\[" + key + " \"([^\"]+)\"\\]");
        Matcher matcher = pattern.matcher(headers);
        return matcher.find() ? matcher.group(1) : "?";
    }

    // Determine if the target user won, lost, or drew
    private static String determineUserResult(String targetUser, String white, String black, String pgnResult) {
        targetUser = targetUser.trim().replace("\"", "");
        white = white.trim().replace("\"", "");
        black = black.trim().replace("\"", "");

        boolean userIsWhite = targetUser.equalsIgnoreCase(white);
        boolean userIsBlack = targetUser.equalsIgnoreCase(black);

        if (!userIsWhite && !userIsBlack) {
            return "(?)";
        }

        switch (pgnResult) {
            case "1-0":
                return userIsWhite ? "(won)" : "(lost)";
            case "0-1":
                return userIsBlack ? "(won)" : "(lost)";
            case "1/2-1/2":
                return "(draw)";
            default:
                return "(?)";
        }
    }

    // Determine user's color in the game
    private static String determineUserColor(String targetUser, String white, String black) {
        targetUser = targetUser.trim().replace("\"", "");
        white = white.trim().replace("\"", "");
        black = black.trim().replace("\"", "");
        
        if (targetUser.equalsIgnoreCase(white)) {
            return "White";
        } else if (targetUser.equalsIgnoreCase(black)) {
            return "Black";
        }
        return "Unknown";
    }

    // Get user's rating for the game
    private static int getUserRating(String targetUser, String white, String black, String whiteElo, String blackElo) {
        try {
            targetUser = targetUser.trim().replace("\"", "");
            white = white.trim().replace("\"", "");
            black = black.trim().replace("\"", "");
            
            if (targetUser.equalsIgnoreCase(white)) {
                return Integer.parseInt(whiteElo);
            } else if (targetUser.equalsIgnoreCase(black)) {
                return Integer.parseInt(blackElo);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
        return 0;
    }

    // Convert time control from "600" or "300+5" format to "X|Y" format
    private static String formatTimeControl(String timeControlRaw) {
        if (timeControlRaw == null || timeControlRaw.equals("?") || timeControlRaw.equals("-")) {
            return "?|?";
        }

        try {
            if (!timeControlRaw.contains("+")) {
                int totalSeconds = Integer.parseInt(timeControlRaw);
                int minutes = totalSeconds / 60;
                return minutes + "|0";
            } else {
                String[] parts = timeControlRaw.split("\\+");
                int baseSeconds = Integer.parseInt(parts[0]);
                int incrementSeconds = Integer.parseInt(parts[1]);
                int baseMinutes = baseSeconds / 60;
                return baseMinutes + "|" + incrementSeconds;
            }
        } catch (NumberFormatException e) {
            return "?|?";
        }
    }

    // Determine game type based on time control and event
    private static String determineGameType(String timeControlRaw, String eventType) {
        if (timeControlRaw == null || timeControlRaw.equals("?") || timeControlRaw.equals("-")) {
            return "Unknown";
        }

        try {
            int totalTime;
            if (timeControlRaw.contains("+")) {
                String[] parts = timeControlRaw.split("\\+");
                totalTime = Integer.parseInt(parts[0]);
            } else {
                totalTime = Integer.parseInt(timeControlRaw);
            }

            int minutes = totalTime / 60;

            if (eventType != null && eventType.contains("Daily")) {
                return "Daily";
            } else if (minutes <= 1) {
                return "Bullet";
            } else if (minutes <= 3) {
                return "Blitz";
            } else if (minutes <= 10) {
                return "Rapid";
            } else if (minutes <= 30) {
                return "Classical";
            } else {
                return "Correspondence";
            }
        } catch (NumberFormatException e) {
            return "Unknown";
        }
    }

    // Clean the move string
    private static String cleanMoves(String moveText) {
        String withoutClocks = moveText.replaceAll("\\{\\[%clk [^}]*\\}\\}", "");
        withoutClocks = withoutClocks.replaceAll("\\{\\[%eval [^}]*\\}\\}", "");
        withoutClocks = withoutClocks.replaceAll("\\s*(1-0|0-1|1/2-1/2)\\s*$", "");
        return withoutClocks.replaceAll("\\s+", " ").trim();
    }

    // Calculate number of moves in a game
    private static int calculateMoveCount(String moves) {
        // Split by move numbers and count actual moves
        String[] tokens = moves.split("\\s+");
        int moveCount = 0;
        
        for (String token : tokens) {
            // Skip move numbers (e.g., "1.", "2.") and result markers
            if (!token.matches("\\d+\\.") && 
                !token.matches("\\d+\\.\\.\\.") &&
                !token.matches("1-0|0-1|1/2-1/2") &&
                !token.isEmpty()) {
                moveCount++;
            }
        }
        
        return moveCount / 2; // Each move has white and black
    }

    // Append detailed statistics to the file
    private static void appendDetailedStatisticsToFile(String fileName, GameStats stats, 
                                                      String username, String year, String month) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            writer.write("\n" + createSeparator(60));
            writer.write("\n" + centerText("CHESS.COM GAME STATISTICS", 60));
            writer.write("\n" + centerText("Username: " + username + " | Period: " + 
                                         (month.equals("0") ? "Year " + year : year + "-" + month), 60));
            writer.write("\n" + createSeparator(60));
            
            // Overall Statistics
            writer.write("\n\n" + centerText("OVERALL STATISTICS", 60));
            writer.write("\n" + formatStats(stats));
            
            // Rating Change Bar
            writer.write("\n\n" + centerText("RATING CHANGES", 60));
            writer.write("\n" + formatRatingChanges(stats));
            
            // Performance by Game Type
            writer.write("\n\n" + centerText("PERFORMANCE BY GAME TYPE", 60));
            writer.write("\n" + formatGameTypeStats(stats));
            
            // Results by Color
            writer.write("\n\n" + centerText("RESULTS BY COLOR", 60));
            writer.write("\n" + formatColorStats(stats));
            
            // Additional Statistics
            writer.write("\n\n" + centerText("ADDITIONAL STATISTICS", 60));
            writer.write("\n" + formatAdditionalStats(stats));
            
            writer.write("\n" + createSeparator(60));
            writer.write("\n" + centerText("Analysis generated by chessextractor made by Divine Coder Of Hell", 60));
            writer.write("\n" + createSeparator(60));
        }
    }
    
    // Format overall statistics
    private static String formatStats(GameStats stats) {
        int total = stats.getTotalGames();
        if (total == 0) return "No games found.";
        
        int won = stats.getWon();
        int lost = stats.getLost();
        int draw = stats.getDraw();
        
        double wonPercent = total > 0 ? (double) won / total * 100 : 0;
        double lostPercent = total > 0 ? (double) lost / total * 100 : 0;
        double drawPercent = total > 0 ? (double) draw / total * 100 : 0;
        
        DecimalFormat df = new DecimalFormat("#.##");
        
        return String.format(
            "Total Games: %d\n" +
            "Won: %d (%s%%) | Lost: %d (%s%%) | Draw: %d (%s%%)\n" +
            "Average Moves per Game: %s\n" +
            "Win Rate: %s%%",
            total, won, df.format(wonPercent), lost, df.format(lostPercent), 
            draw, df.format(drawPercent), df.format(stats.getAverageMoves()), 
            df.format(wonPercent)
        );
    }
    
    // Format rating changes
    private static String formatRatingChanges(GameStats stats) {
        Map<String, RatingTracker> trackers = stats.getRatingTrackers();
        if (trackers.isEmpty()) return "No rating data available.";
        
        StringBuilder sb = new StringBuilder();
        DecimalFormat df = new DecimalFormat("#");
        
        for (Map.Entry<String, RatingTracker> entry : trackers.entrySet()) {
            String gameType = entry.getKey();
            RatingTracker tracker = entry.getValue();
            int change = tracker.getRatingChange();
            String changeStr = change >= 0 ? "+" + change : String.valueOf(change);
            
            sb.append(String.format("%-12s: %s (Start: %d, End: %d, Avg: %d)\n", 
                    gameType, changeStr, tracker.getStartingRating(), 
                    tracker.getLatestRating(), tracker.getAverageRating()));
        }
        
        return sb.toString();
    }
    
    // Format game type statistics
    private static String formatGameTypeStats(GameStats stats) {
        Map<String, GameTypeStats> typeStats = stats.getGameTypeStats();
        if (typeStats.isEmpty()) return "No game type data available.";
        
        StringBuilder sb = new StringBuilder();
        DecimalFormat df = new DecimalFormat("#.##");
        
        for (GameTypeStats gtStats : typeStats.values()) {
            if (gtStats.getTotal() == 0) continue;
            
            sb.append(String.format("\n%s (Total: %d):\n", gtStats.getGameType(), gtStats.getTotal()));
            sb.append(String.format("  Win: %d (%s%%) | Loss: %d (%s%%) | Draw: %d (%s%%)\n",
                    gtStats.getWon(), df.format(gtStats.getWinRate()),
                    gtStats.getLost(), df.format(gtStats.getLossRate()),
                    gtStats.getDraw(), df.format(gtStats.getDrawRate())));
            sb.append(String.format("  Avg Moves: %s | Avg Rating: %d\n",
                    df.format(gtStats.getAverageMoves()),
                    gtStats.getAverageRating()));
            sb.append(String.format("  Rating Range: %d (%d - %d, Latest: %d)\n",
                    gtStats.getRatingRange(),
                    gtStats.getMinRating(),
                    gtStats.getMaxRating(),
                    gtStats.getLatestRating()));
        }
        
        return sb.toString();
    }
    
    // Format color statistics
    private static String formatColorStats(GameStats stats) {
        StringBuilder sb = new StringBuilder();
        DecimalFormat df = new DecimalFormat("#.##");
        
        // White statistics
        int whiteTotal = stats.getWhiteGames();
        if (whiteTotal > 0) {
            double whiteWinRate = (double) stats.getWhiteWon() / whiteTotal * 100;
            double whiteLossRate = (double) stats.getWhiteLost() / whiteTotal * 100;
            double whiteDrawRate = (double) stats.getWhiteDraw() / whiteTotal * 100;
            
            sb.append(String.format("\nAs White (%d games):\n", whiteTotal));
            sb.append(String.format("  Win: %d (%s%%) | Loss: %d (%s%%) | Draw: %d (%s%%)\n",
                    stats.getWhiteWon(), df.format(whiteWinRate),
                    stats.getWhiteLost(), df.format(whiteLossRate),
                    stats.getWhiteDraw(), df.format(whiteDrawRate)));
        }
        
        // Black statistics
        int blackTotal = stats.getBlackGames();
        if (blackTotal > 0) {
            double blackWinRate = (double) stats.getBlackWon() / blackTotal * 100;
            double blackLossRate = (double) stats.getBlackLost() / blackTotal * 100;
            double blackDrawRate = (double) stats.getBlackDraw() / blackTotal * 100;
            
            sb.append(String.format("\nAs Black (%d games):\n", blackTotal));
            sb.append(String.format("  Win: %d (%s%%) | Loss: %d (%s%%) | Draw: %d (%s%%)\n",
                    stats.getBlackWon(), df.format(blackWinRate),
                    stats.getBlackLost(), df.format(blackLossRate),
                    stats.getBlackDraw(), df.format(blackDrawRate)));
        }
        
        return sb.toString();
    }
    
    // Format additional statistics
    private static String formatAdditionalStats(GameStats stats) {
        StringBuilder sb = new StringBuilder();
        DecimalFormat df = new DecimalFormat("#.##");
        
        // Move statistics
        List<Integer> moves = stats.getMoveCounts();
        if (!moves.isEmpty()) {
            Collections.sort(moves);
            int shortest = moves.get(0);
            int longest = moves.get(moves.size() - 1);
            int median = moves.get(moves.size() / 2);
            
            sb.append(String.format("Shortest Game: %d moves\n", shortest));
            sb.append(String.format("Longest Game: %d moves\n", longest));
            sb.append(String.format("Median Game Length: %d moves\n", median));
        }
        
        // Game type distribution
        Map<String, GameTypeStats> typeStats = stats.getGameTypeStats();
        if (!typeStats.isEmpty()) {
            sb.append("\nGame Type Distribution:\n");
            for (Map.Entry<String, GameTypeStats> entry : typeStats.entrySet()) {
                int total = entry.getValue().getTotal();
                double percentage = (double) total / stats.getTotalGames() * 100;
                sb.append(String.format("  %-12s: %d games (%s%%)\n", 
                        entry.getKey(), total, df.format(percentage)));
            }
        }
        
        return sb.toString();
    }
    
    // Create separator line
    private static String createSeparator(int length) {
        return "=".repeat(length);
    }
    
    // Center text
    private static String centerText(String text, int width) {
        int padding = (width - text.length()) / 2;
        if (padding < 0) padding = 0;
        return " ".repeat(padding) + text;
    }
    
    // Print console summary
    private static void printConsoleSummary(GameStats stats) {
        System.out.println("\n=== SUMMARY ===");
        System.out.println("Total Games Processed: " + stats.getTotalGames());
        System.out.println("Win Rate: " + String.format("%.2f", (double) stats.getWon() / stats.getTotalGames() * 100) + "%");
        System.out.println("Average Moves per Game: " + String.format("%.1f", stats.getAverageMoves()));
        
        Map<String, GameTypeStats> typeStats = stats.getGameTypeStats();
        if (!typeStats.isEmpty()) {
            System.out.println("\nGame Types Played:");
            for (Map.Entry<String, GameTypeStats> entry : typeStats.entrySet()) {
                System.out.println("  " + entry.getKey() + ": " + entry.getValue().getTotal() + " games");
            }
        }
        
        // Show rating changes
        Map<String, RatingTracker> trackers = stats.getRatingTrackers();
        if (!trackers.isEmpty()) {
            System.out.println("\nRating Changes:");
            for (Map.Entry<String, RatingTracker> entry : trackers.entrySet()) {
                int change = entry.getValue().getRatingChange();
                String changeStr = change >= 0 ? "+" + change : String.valueOf(change);
                System.out.println("  " + entry.getKey() + ": " + changeStr);
            }
        }
    }
}