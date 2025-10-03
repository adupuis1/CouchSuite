


#`CouchLauncherFX/LAUNCHERGUIAGENT.md`

```markdown
# Goal

Build a runnable JavaFX client (CouchLauncherFX) that uses the images found in  
`CouchSuite/CouchSuiteLauncherClientDesignReference/Launcher layout/` as references.  
On boot → Connect Controller → Login/Add User → Hub (Home/Gaming/TV).  
New machines go straight to Home after first user creation.

## Inputs (assets)

- Images: `connect controller.png`, `select user.png`, `home.png`, `gaming.png`, `tv.png`  
- Icons: always pull from `/CouchLauncherFX/resources/app/assets/` first, fallback to Launcher layout images.

## Screen flow (state machine)

BOOT  
 └─ CONNECT_CONTROLLER → SELECT_USER → HUB (Home | Gaming | TV)

- Select User: shows “+” if no users exist.  
- Hub: tabbed interface, default Home.

## Header Icons
- User icon → open profile / switch user.  
- Controller icon → controller settings / connect new.  
- Wi-Fi icon → reflects connection state (no action for now).  

## Game Integration
- Home tab calls server `/charts/top10` to display current top 10 games.  
- Selecting a game → open **Game Page**:  
  - Display info (cover, description, rating).  
  - If owned + downloaded → show green **Play** button.  
  - Play triggers server `/play/{game_id}` to validate + launch.  

## Notes
- Keep Home, Gaming, TV pages visually consistent.  
- Ensure smooth navigation via controller or keyboard.  
- Use icons and header consistently across tabs.  

