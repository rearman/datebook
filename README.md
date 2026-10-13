# Date Book and To Do List

Two Android apps after the Palm OS 3.5 Date Book and To Do List. One APK installs both apps. Each app has its own icon. The two apps use the same records, so the Date Book Agenda view shows your To Do items.

## Build the APK with GitHub

1. Put all files from this folder into the `rearman/datebook` repository. Keep the folder structure. The `.github` folder must go in too.
2. Open the **Actions** tab in the repository.
3. Select **Build APK**, then select **Run workflow**. The workflow also runs after each push to `main`.
4. When the run is complete, open it. Download **datebook.apk** from the **Artifacts** list, then install it on your phone.

The project has a fixed signing key (`app/debug.keystore`). Thus a new build installs over the old one and keeps your data.

## Features

### Date Book

- Day view: time lines from the start time to the end time, time bars for overlapping events, untimed events at the top, and note, alarm and repeat icons. Tap a line and type to make an event. With Show Time Bars, an event that ends between hours gets an empty line at its end time.
- Week view: a seven-day time grid. Tap an event to see its description. Tap it again to open its day. Touch and hold an event to edit it.
- Month view: marks for timed events, untimed events (+) and daily repeating events (dashed line).
- Agenda view: the events for one day, plus all open To Do items.
- Swipe left for the next day, week or month. Swipe right for the previous one. In Day view, a swipe moves one day and the arrows move one week.
- Event Details: time, date, alarm, repeat, private and note.
- Repeat: daily, weekly (on selected days), monthly by day or by date, yearly, with an optional end date. For changes to a repeating event, select Current or All. For deletions, select Current, Future or All.
- Alarms ring while the app is closed. Preferences set the alarm preset, the number of reminders and the interval. Snooze adds 5 minutes.

### To Do List

- Priority 1 to 5, due date, category, private and note.
- Show options: sort order, completed items, only due items, completion date, and the columns to show.
- Up to 15 categories.

### Both

- The view icons at the bottom of Date Book select Day, Week, Month or Agenda.
- **Menu** (the three-line icon at the bottom right, or a tap on the title tab) opens the Record, Edit and Options menus.
- **Find** (the magnifier icon at the bottom right) searches the descriptions and notes of all records.
- Font, Security (show, mask or hide private records), Purge, and a Green LCD screen option.
- All screens use the proportions of the Palm IIIxe: the screen width is 160 Palm pixels, and the Font option selects the Palm small, standard or large font size.
- Export Backup and Import Backup save and load all records as a JSON file.

## Limits

- Graffiti and pen gestures are not included.
- Beam and Phone Lookup are not included.
- Android 8.0 or later is necessary.
