package models;

/**
 * One worker as read from a row of the monthly attendance sheet.
 */
public class Worker {
    /** Company name used in the sheet for workers not assigned to any company. */
    public static final String UNASSIGNED_COMPANY = "לא משוייך";

    private String location;
    private String profession;
    private String company;
    private String countryOfOrigin;
    private String andromedaWorkerId;
    private String name;
    private String passportNumber;
    private WorkerState state;
    private String tenure; // how long the worker has been with us; only filled for unassigned workers

    /** A worker's state on a given day, and the symbol the sheet uses for it in that day's column. */
    public enum WorkerState {
        NOT_WORKING(""),
        WORKING("1"),
        SICK("ח"),
        VACATION("א"), // shown as אינטרויזה in the report
        REFUSING_TO_WORK("ס");

        private final String symbol;

        WorkerState(String symbol) {
            this.symbol = symbol;
        }

        /** Returns the state for a sheet symbol, or {@code null} if the symbol is not one of the known ones. */
        public static WorkerState translateSymbolToState(String symbol) {
            for (WorkerState state : values()) {
                if (state.symbol.equals(symbol)) return state;
            }
            return null;
        }
    }

    public Worker() {
    }

    /** Parameter order follows the sheet's column order. */
    public Worker(String location, String profession, String company, String andromedaWorkerId,
                  String countryOfOrigin, String name, String passportNumber, WorkerState state, String tenure) {
        this.location = location;
        this.profession = profession;
        this.company = company;
        this.andromedaWorkerId = andromedaWorkerId;
        this.countryOfOrigin = countryOfOrigin;
        this.name = name;
        this.passportNumber = passportNumber;
        this.state = state;
        this.tenure = tenure;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getProfession() {
        return profession;
    }

    public void setProfession(String profession) {
        this.profession = profession;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getCountryOfOrigin() {
        return countryOfOrigin;
    }

    public void setCountryOfOrigin(String countryOfOrigin) {
        this.countryOfOrigin = countryOfOrigin;
    }

    public String getAndromedaWorkerId() {
        return andromedaWorkerId;
    }

    public void setAndromedaWorkerId(String andromedaWorkerId) {
        this.andromedaWorkerId = andromedaWorkerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPassportNumber() {
        return passportNumber;
    }

    public void setPassportNumber(String passportNumber) {
        this.passportNumber = passportNumber;
    }

    public WorkerState getState() {
        return state;
    }

    public void setState(WorkerState state) {
        this.state = state;
    }

    public String getTenure() {
        return tenure;
    }

    public void setTenure(String tenure) {
        this.tenure = tenure;
    }

    @Override
    public String toString() {
        return String.format(
                "Worker[%s | ID: %s | Passport: %s | %s @ %s | Location: %s | From: %s | State: %s | Tenure: %s]",
                ltr(name), ltr(andromedaWorkerId), ltr(passportNumber), ltr(profession), ltr(company),
                ltr(location), ltr(countryOfOrigin), state == null ? "UNKNOWN" : state, ltr(tenure));
    }

    // Left-to-right mark: an invisible character that keeps Hebrew values from
    // reordering the separators around them, so the line displays in English order.
    private static final String LRM = "‎";

    private static String ltr(String value) {
        return LRM + value + LRM;
    }
}
