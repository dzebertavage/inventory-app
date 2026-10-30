# Inventory

A portrait-mode Android tablet app for filling out the monthly warehouse inventory spreadsheet.

- Open the `.xlsx` from Gmail (tap the attachment → open with **Inventory**) or from the app's **Open spreadsheet** button.
- Each tab gets one large card per item: **Item Description** first, **Vendor** second, then Unit, Par and Item ID.
- An on-screen number pad (with decimal point) fills in **Qty on Hand**, **5 lb Bags**, **Lbs on Mail Order Shelf** and **Lbs in Buckets**. **Blank** leaves a cell empty, which is different from 0.
- Values already in the sheet are shown and can be overwritten. Long-press a field to undo an entry.
- The keypad area has two tabs: **Count** (the number pad) and **Calculator**. The calculator has the basic Pixel-style keys (AC, ( ), %, ÷ × − +, ⌫, =) and a scrollable **History**; tap a past result to reuse it.
  **Use answer** rounds the result to 2 decimals and drops it into the selected field's display on the Count tab. It is saved only when you press **Next**.
  The calculation and history are kept when you switch tabs or close the app; AC clears the current calculation.
- **Qty on Hand** turns red with "AT / BELOW PAR" when it is at or below Par.
- Progress saves after every entry.
- **Export** warns about anything not yet counted or marked Blank. It then writes
  `Internal storage/Inventories/<YYYY>/Monthly Inventory MM-DD-YY.xlsx` and creates the year folder if needed.
  A second export on the same day becomes `… (2).xlsx`. The first export asks you to pick the `Inventories` folder once.

## What export changes

Export changes only the values of the cells you entered or marked Blank. Every other part of the file is copied byte-for-byte: other tabs, cost columns, formulas, number formats, conditional formatting, column widths and Google Sheets metadata.
These tabs are never shown and never touched:
Inventory - Green Beans, Roaster & Mail Order Shelves, Equipment, BA Equipment, Totals, Sheet5.
Any other tab with an "Item Description" header row is treated as a tab to fill in.

## Building

Every push to `main` builds the APK with GitHub Actions and publishes it under **Releases** as `Inventory-1.N.apk`.
On the tablet, open the latest release, download the APK and open it to install or update. The first time, Android asks you to allow installs from Chrome/Files.

The APK is always signed with `app/release.keystore`, so updates install over the existing app and keep saved progress.

Local build: `./gradlew assembleRelease` (requires the Android SDK).
