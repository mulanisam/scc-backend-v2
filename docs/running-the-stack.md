# Running the stack: local, LAN, and later the cloud

What the shop's launcher script does today - three Terminal windows via `osascript`,
running `npm start` and `java -jar` - works for one person on the Mac itself. It breaks
the moment somebody tries it from a phone on the same wifi, for a reason that has
nothing to do with the launcher: the frontend called `http://localhost:8080` no matter
where it was opened from, and on a phone `localhost` means the phone.

That is fixed now (`src/config/axiosConfig.js` on the frontend). Everything else here is
what is left to do the same job properly, in order of how much it actually matters.

## 1. The origin bug - fixed, and why it matters more than the launcher

`API_BASE_URL` was a literal string, `http://localhost:8080`. It is now:

```js
export const API_BASE_URL =
  process.env.REACT_APP_API_URL || `http://${window.location.hostname}:8080`;
```

Opened as `localhost:3000`, it calls `localhost:8080` - unchanged. Opened as
`192.168.1.50:3000` from a phone, it calls `192.168.1.50:8080`, because that is the
address the phone's browser actually loaded the page from. No configuration, and no
per-device setup.

`REACT_APP_API_URL` overrides it outright, and that is the same knob cloud hosting
needs: set it to `https://api.yourdomain.com` when you run `npm run build` for the
cloud deployment, and the fallback above never runs. This was not a LAN-only patch -
it is the one correct way to point the frontend at an API, in every one of the three
situations this document covers.

## 2. Let the LAN in on the backend side too

Nothing here needed to change - Spring Boot binds every interface on the Mac by
default (`server.address` is not set anywhere, and that is deliberate) - but the CORS
list is explicit on purpose, "never a wildcard: `*` lets any site on the internet call
this API with a signed-in user token" (`SecurityConfig.java`). Deliberate means it has
to be told about the new origin.

`application-dev.properties` has:

```properties
frontend.url=http://localhost:3000
```

It is comma-separated. Add the Mac's LAN address rather than replacing the existing
one:

```properties
frontend.url=http://localhost:3000,http://192.168.1.50:3000
```

Find that address with `ipconfig getifaddr en0` (or `en1` on Wi-Fi-only Macs) in
Terminal. It is worth giving the Mac a **static IP or a DHCP reservation** in the
router's settings - a router-assigned address that changes after a power cut turns
into "the app stopped working on everyone's phone" with no code at fault.

Production already does this correctly and needs no change: `frontend.url=${FRONTEND_URL}`
reads a real environment variable, so the cloud deployment sets one value and CORS
follows it.

## 3. Serve a build, not the dev server

`npm start` is the React development server: hot reload, an error overlay, and a
noticeably heavier memory footprint - all built for one person actively editing code,
none of it useful for a handful of phones reading a live sales screen over wifi all
day. It is also never what runs in production anywhere, so treating the shop's Mac as
though it already were production is the right habit to build now rather than at the
cloud migration.

```bash
npm run build                 # produces build/, whenever the code changes
npx serve -s build -l 3000    # serves it, no install needed
```

`serve` is a small static file server; `-s` sends every unknown path to `index.html`,
which a single-page app's client-side routing needs. Re-run `npm run build` after
pulling changes - the served files are a snapshot, not a live view of the source.

## 4. Replace the three Terminal windows

The `osascript` script's real job is "start both processes and keep them running,"
and three visible Terminal windows are a side effect of doing that the hard way. Two
options, either is a genuine improvement over what exists:

### pm2 - recommended here

One tool, one command to see everything, and the same tool still works unchanged on
a Linux cloud VM later - which is not true of a macOS-specific launcher.

```bash
npm install -g pm2
```

`ecosystem.config.js`, anywhere convenient (e.g. `~/scripts/`):

```js
module.exports = {
  apps: [
    {
      name: 'scc-backend',
      cwd: '/Users/sohelchickencentre/SOHEL/backend',
      script: 'java',
      args: '-jar backend-0.0.1-SNAPSHOT.jar',
    },
    {
      name: 'scc-frontend',
      cwd: '/Users/sohelchickencentre/SOHEL/scc-frontend',
      script: 'npx',
      args: 'serve -s build -l 3000',
    },
  ],
};
```

```bash
pm2 start ~/scripts/ecosystem.config.js   # starts both, no Terminal windows left open
pm2 status                                # is everything up
pm2 logs scc-backend --lines 100          # what it printed
pm2 restart scc-frontend                  # after a new build
pm2 startup && pm2 save                   # both come up automatically after a reboot
```

A crash restarts on its own - `KeepAlive`, effectively, without writing any plist.

### launchd - the native macOS alternative

More "correct" for macOS specifically, and worth moving to later if pm2 ever feels
like the wrong layer - but it means hand-writing XML property lists, which is more
fragile to get right by hand than the pm2 config above. Skip it for now; it buys
nothing that pm2 does not already give the shop today.

### Not Electron

Packaging this as an Electron desktop app was worth ruling out explicitly: it wraps
one desktop's browser chrome around the frontend, which does nothing for a phone on
the same wifi - that problem is solved above, entirely on the network side, before an
Electron shell would even enter the picture. It would be real engineering effort spent
on a stated "temporary, until the cloud" setup, for a benefit that does not exist here.

## 5. The backup should not depend on somebody starting the apps

The current script runs `backup_mysql.sh` every time the two apps are launched -
which means a backup happens only on days somebody restarts the stack, possibly more
than once, and not at all on a day nothing crashes. Give it its own schedule instead,
independent of pm2 or the apps' lifecycle:

```bash
crontab -e
# 0 2 * * * /bin/bash ~/scripts/backup_mysql.sh >> ~/scripts/backup.log 2>&1
```

Once a day, at 2am, whether or not anyone touches the Mac that day.

## 6. What this sets up for the cloud move

Nothing above is thrown away when that happens:

- `REACT_APP_API_URL` is the same variable a cloud build sets, to the real domain
  instead of falling back to `window.location`.
- `FRONTEND_URL` is the same variable the backend already reads in the `prod` profile.
- `pm2` runs identically on a Linux VM - the ecosystem file's `cwd` and `args` change,
  the tool and the commands do not.
- A `build/` folder served statically is exactly the artifact most cloud static
  hosting (or an nginx container) wants; `npm start` never would have been.

The one thing that does change later: same-origin hosting (frontend and backend
behind one domain, one reverse proxy) removes the CORS question entirely rather than
managing a list of allowed origins. Worth doing at that point; not worth building now
for a LAN of a few devices in one shop.
