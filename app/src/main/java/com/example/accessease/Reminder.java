package com.example.accessease;

public class Reminder {
    private String title;
    private String description;
    private long   time;
    private boolean medication;
    private boolean completed;
    private String  firestoreId; // ★ tracks reminders added by caregiver via Firestore

    // Full constructor (used when user adds reminders locally)
    public Reminder(String title, String description, long time, boolean medication) {
        this.title       = title;
        this.description = description;
        this.time        = time;
        this.medication  = medication;
        this.completed   = false;
    }

    // ★ No-arg constructor required by Gson and caregiver listener
    public Reminder() {}

    public String  getTitle()       { return title; }
    public String  getDescription() { return description; }
    public long    getTime()        { return time; }
    public boolean isMedication()   { return medication; }
    public boolean isCompleted()    { return completed; }
    public String  getFirestoreId() { return firestoreId; }

    public void setTitle(String title)             { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setTime(long time)                 { this.time = time; }
    public void setMedication(boolean medication)  { this.medication = medication; }
    public void setCompleted(boolean completed)    { this.completed = completed; }
    public void setFirestoreId(String id)          { this.firestoreId = id; }
}