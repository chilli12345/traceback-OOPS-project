import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

interface Searchable {
    boolean matches(String query);
}

abstract class LostItem implements Searchable {
    private final String id;
    private final String name;
    private final String description;
    private final String location;
    private final String dateCollected;

    public LostItem(String id, String name, String description,
                    String location, String dateCollected) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.location = location;
        this.dateCollected = dateCollected;
    }

    public LostItem(String id, String name, String description, String location) {
        this(id, name, description, location, "Unknown");
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public String getDateCollected() { return dateCollected; }

    @Override
    public boolean matches(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).contains(q)
            || description.toLowerCase(Locale.ROOT).contains(q)
            || location.toLowerCase(Locale.ROOT).contains(q);
    }

    public abstract String getType();

    public String toFileRecord() {
        return getType() + "|"
            + encode(id) + "|"
            + encode(name) + "|"
            + encode(description) + "|"
            + encode(location) + "|"
            + encode(dateCollected);
    }

    protected static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    protected static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}

class ElectronicItem extends LostItem {
    public ElectronicItem(String id, String name, String description,
                          String location, String dateCollected) {
        super(id, name, description, location, dateCollected);
    }

    public ElectronicItem(String id, String name, String description, String location) {
        super(id, name, description, location);
    }

    @Override public String getType() { return "Electronic"; }
}

class AccessoryItem extends LostItem {
    public AccessoryItem(String id, String name, String description,
                         String location, String dateCollected) {
        super(id, name, description, location, dateCollected);
    }

    @Override public String getType() { return "Accessory"; }
}

class OtherItem extends LostItem {
    public OtherItem(String id, String name, String description,
                     String location, String dateCollected) {
        super(id, name, description, location, dateCollected);
    }

    @Override public String getType() { return "Other"; }
}

class Claim {
    private final String claimantName;
    private final String dateLost;

    public Claim(String claimantName, String dateLost) {
        this.claimantName = claimantName;
        this.dateLost = dateLost;
    }

    public String getClaimantName() { return claimantName; }
    public String getDateLost() { return dateLost; }
}

class ItemNotFoundException extends Exception {
    public ItemNotFoundException(String message) { super(message); }
}

class LostAndFoundSystem {
    private final ArrayList<LostItem> items = new ArrayList<>();
    private final String dataFile = "traceback-data.txt";

    public synchronized void registerItem(LostItem item) {
        items.add(item);
    }

    public synchronized int nextId() {
        int highest = 0;
        for (LostItem item : items) {
            try {
                highest = Math.max(highest, Integer.parseInt(item.getId()));
            } catch (NumberFormatException ignored) {
            }
        }
        return highest + 1;
    }

    public synchronized ArrayList<LostItem> searchItems(String query) {
        ArrayList<LostItem> results = new ArrayList<>();
        for (LostItem item : items) {
            if (item.matches(query)) results.add(item);
        }
        return results;
    }

    public synchronized ArrayList<LostItem> searchItems(String query, String dateLost) {
        ArrayList<LostItem> results = new ArrayList<>();
        for (LostItem item : items) {
            if (item.matches(query) && item.getDateCollected().equals(dateLost)) {
                results.add(item);
            }
        }
        return results;
    }

    public synchronized LostItem getItemById(String id) throws ItemNotFoundException {
        for (LostItem item : items) {
            if (item.getId().equals(id)) return item;
        }
        throw new ItemNotFoundException("No item exists with ID " + id + ".");
    }

    public synchronized boolean returnItem(String id) throws ItemNotFoundException, IOException {
        LostItem item = getItemById(id);
        items.remove(item);
        saveData();
        return true;
    }

    public synchronized void saveData() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                Paths.get(dataFile), StandardCharsets.UTF_8)) {
            for (LostItem item : items) {
                writer.write(item.toFileRecord());
                writer.newLine();
            }
        }
    }

    public synchronized void loadData() throws IOException {
        Path path = Paths.get(dataFile);
        if (!Files.exists(path)) return;

        ArrayList<LostItem> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\\|", -1);
                try {
                    String id = decodeLegacyOrCurrent(p, 1);
                    String name = decodeLegacyOrCurrent(p, 2);
                    String description = decodeLegacyOrCurrent(p, 3);
                    String location = decodeLegacyOrCurrent(p, 4);
                    String dateCollected;

                    if (p.length == 6) {
                        dateCollected = decodeLegacyOrCurrent(p, 5);
                    } else if (p.length == 7) {
                        if ("true".equalsIgnoreCase(p[6])) continue;
                        dateCollected = "Unknown (older record)";
                    } else {
                        System.err.println("Skipping invalid data record.");
                        continue;
                    }

                    LostItem item;
                    switch (p[0]) {
                        case "Electronic":
                            item = new ElectronicItem(id, name, description, location, dateCollected);
                            break;
                        case "Accessory":
                            item = new AccessoryItem(id, name, description, location, dateCollected);
                            break;
                        case "Other":
                            item = new OtherItem(id, name, description, location, dateCollected);
                            break;
                        default:
                            System.err.println("Unknown item type; skipping record.");
                            continue;
                    }
                    loaded.add(item);
                } catch (IllegalArgumentException ex) {
                    System.err.println("Skipping invalid encoded record.");
                }
            }
        }
        items.clear();
        items.addAll(loaded);
        saveData();
    }

    private String decodeLegacyOrCurrent(String[] parts, int index) {
        return LostItem.decode(parts[index]);
    }

    public synchronized void generateReport() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                Paths.get("traceback-report.txt"), StandardCharsets.UTF_8)) {
            writer.write("TRACEBACK - LOST AND FOUND INVENTORY REPORT");
            writer.newLine();
            writer.write("==========================================");
            writer.newLine();
            writer.newLine();
            for (LostItem item : items) {
                writer.write("ID: " + item.getId()
                    + " | Type: " + item.getType()
                    + " | Name: " + item.getName()
                    + " | Date collected: " + item.getDateCollected()
                    + " | Found at: " + item.getLocation());
                writer.newLine();
            }
            writer.newLine();
            writer.write("Items currently in inventory: " + items.size());
        }
    }

    public synchronized void printSummary() {
        System.out.println("\n--- INVENTORY SUMMARY ---");
        System.out.println("Items currently in inventory: " + items.size());
    }

    static class AppConfig {
        static final String APP_NAME = "TraceBack";
    }
}

class BackgroundSaver extends Thread {
    private final LostAndFoundSystem system;
    private volatile boolean running = true;

    public BackgroundSaver(LostAndFoundSystem system) {
        this.system = system;
        setName("TraceBack-Autosave");
        setDaemon(true);
    }

    @Override public void run() {
        while (running) {
            try {
                Thread.sleep(30000);
                if (running) system.saveData();
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                System.err.println("\n[Background] Autosave failed: " + e.getMessage());
            }
        }
    }

    public void stopSaving() {
        running = false;
        interrupt();
    }
}

public class Main {
    private static final Scanner sc = new Scanner(System.in);
    private static final LostAndFoundSystem system = new LostAndFoundSystem();
    private static BackgroundSaver saver;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    public static void main(String[] args) {
        try {
            system.loadData();
        } catch (IOException e) {
            System.err.println("Could not load records: " + e.getMessage());
        }

        saver = new BackgroundSaver(system);
        saver.start();

        System.out.println("================================");
        System.out.println("       TRACEBACK SYSTEM");
        System.out.println("================================");

        boolean running = true;
        while (running) {
            printMenu();
            String choice = readLine("Choose an option: ");
            try {
                switch (choice) {
                    case "1": registerItem(); break;
                    case "2": searchItems(); break;
                    case "3": handleReturn(); break;
                    case "4": system.printSummary(); break;
                    case "5":
                        system.generateReport();
                        System.out.println("Report saved to traceback-report.txt");
                        break;
                    case "6":
                        system.saveData();
                        System.out.println("Inventory saved.");
                        break;
                    case "0": running = false; break;
                    default: System.out.println("Invalid menu choice.");
                }
            } catch (ItemNotFoundException e) {
                System.out.println("Item error: " + e.getMessage());
            } catch (IOException e) {
                System.out.println("File error: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid input: " + e.getMessage());
            }
            System.out.println();
        }

        saver.stopSaving();
        try {
            system.saveData();
            System.out.println("Final save complete.");
        } catch (IOException e) {
            System.err.println("Final save failed: " + e.getMessage());
        }
        System.out.println("Thank you for using TraceBack.");
        sc.close();
    }

    private static void printMenu() {
        System.out.println("1. Register a found item");
        System.out.println("2. Search inventory items");
        System.out.println("3. Help a person retrieve an item");
        System.out.println("4. View inventory summary");
        System.out.println("5. Generate report");
        System.out.println("6. Save inventory now");
        System.out.println("0. Exit");
    }

    private static void registerItem() throws IOException {
        System.out.println("\nITEM CATEGORY");
        System.out.println("1. Electronic");
        System.out.println("2. Accessory");
        System.out.println("3. Other");
        String type = readLine("Category: ");
        String name = readLine("Item name: ");
        String description = readLine("Public description: ");
        String location = readLine("Where was it found? ");
        String dateCollected = readDate("Date the item was collected by Lost & Found (DD-MM-YYYY): ");

        if (name.isEmpty()) throw new IllegalArgumentException("Item name is required.");
        String id = String.valueOf(system.nextId());
        LostItem item;
        switch (type) {
            case "1": item = new ElectronicItem(id, name, description, location, dateCollected); break;
            case "2": item = new AccessoryItem(id, name, description, location, dateCollected); break;
            case "3": item = new OtherItem(id, name, description, location, dateCollected); break;
            default: throw new IllegalArgumentException("Choose category 1, 2, or 3.");
        }
        system.registerItem(item);
        system.saveData();
        System.out.println("Item registered. Reference ID: " + id);
    }

    private static void searchItems() {
        String query = readLine("What item did the person lose? Enter a name or keyword: ");
        ArrayList<LostItem> results = system.searchItems(query);
        printMatches(results);
    }

    private static void handleReturn() throws IOException, ItemNotFoundException {
        String claimant = readLine("Claimant name: ");
        String query = readLine("What item did they lose? Enter a name or keyword: ");
        String dateLost = readDate("Date they lost it (DD-MM-YYYY): ");
        Claim claim = new Claim(claimant, dateLost);

        ArrayList<LostItem> results = system.searchItems(query, dateLost);
        System.out.println("\nItems matching the item name and reported lost date:");
        if (results.isEmpty()) {
            System.out.println("No matching items are currently in the inventory.");
        } else {
            printMatches(results);
        }
        System.out.println("\nDate reported lost by " + claim.getClaimantName() + ": " + claim.getDateLost());
        System.out.println("Cross-reference the item details and dates before confirming a return.");
        String returnedId = readLine("Enter the ID of the item returned to them, or N if no item was returned: ");

        if (returnedId.equalsIgnoreCase("N")) {
            System.out.println("No item returned. Inventory has not been changed.");
            return;
        }
        if (returnedId.isEmpty()) throw new IllegalArgumentException("Enter an item ID or N.");

        boolean inResults = false;
        for (LostItem item : results) {
            if (item.getId().equals(returnedId)) {
                inResults = true;
                break;
            }
        }
        if (!inResults) {
            System.out.println("That ID was not in the displayed matching list. Nothing was deleted.");
            return;
        }

        LostItem returnedItem = system.getItemById(returnedId);
        system.returnItem(returnedId);
        System.out.println("Item '" + returnedItem.getName() + "' (ID " + returnedId + ") marked as returned and removed from traceback-data.txt.");
    }

    private static void printMatches(ArrayList<LostItem> results) {
        if (results.isEmpty()) {
            System.out.println("No matching items found.");
            return;
        }
        System.out.println("\nMATCHING ITEMS — cross-reference with the person:");
        for (LostItem item : results) {
            System.out.println("ID: " + item.getId()
                + " | Type: " + item.getType()
                + " | Name: " + item.getName()
                + " | Description: " + item.getDescription()
                + " | Found at: " + item.getLocation()
                + " | Date collected: " + item.getDateCollected());
        }
    }

    private static String readDate(String prompt) {
        String value = readLine(prompt);
        try {
            LocalDate.parse(value, DATE_FORMAT);
            return value;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Use a valid date in DD-MM-YYYY format.");
        }
    }

    private static String readLine(String prompt) {
        System.out.print(prompt);
        return sc.nextLine().trim();
    }
}
