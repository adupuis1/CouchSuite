#Goal

Build a runnable JavaFX client (CouchLauncherFX) that uses the images found in
CouchSuite/CouchSuiteLauncherClientDesignReference/Launcher layout/
as references. These images are just references, youre to ask me questions about how ui should interact, such as buttons actions and scrolling if not provided by me. At boot, show Connect Controller, once a connection to a valid controller has been made then Select User (or Add User if none), once a valid user is logged on, then the tabbed Home / Gaming / TV hub. On new machines, go straight to Home after the first user is created.

#Inputs (assets)

Use exactly these file names if present (case-insensitive match). If a name differs, find the closest match by keywords in brackets:

connect controller.png (or: connect, controller) – full-screen background for first boot view

select user.png (or: select, user) – background for user picker

home.png (or: home) – background or header glyph for Home tab

gaming.png (or: gaming) – background or header glyph for Gaming tab

tv.png (or: tv) – background or header glyph for TV tab

Copy all images from the folder into the runtime resources at: /launcher_layout


#Screen flow (state machine)
BOOT
 └─> CONNECT_CONTROLLER
      - When any input is detected (controller or keyboard), Continue
 └─> SELECT_USER
      - If users.json is empty → show only “+” (Add User)
      - On “+” create profile, then proceed
 └─> HUB (Home|Gaming|TV tabs; default = Home)

#ICONS
In addition to screen images, always pull app, user, and system icons from:
/CouchLauncherFX/resources/app/assets/

These icons override defaults if a matching file exists.
If both a Launcher layout image and a resources/app/assets/ icon exist for the same UI element, prefer the resources/app/assets/ icon.

If no icon is found in resources/app/assets/, fall back to Launcher layout image or text.

Icons have been sorted into folders. please use them when you see most fit. Home, gaming, and tv page should all contain the same header format with user, controller, and wifi icon. in this header section, users can click user icon to view profile or change user, controller icon can be pressed to view controller settings or to connect another controller, wifi icon has no action on press for now but can be selected. the wifi icon should change dpeending on connection status accordingly.


