Here is a list of things todo. They should be applied to both the android auto app and the non-auto android phone app.

1. If disconnected, the app should be continually trying to reconnect to the car's OBD-II device. So keep trying to connect in the background until successful. No need for the "connect" button - or "disconnect" button (if it exists).
2. Preload tiles in a radius around the last known location of the car. Use a background service to do this.
3. Increase the frequency of the GPS updates when the car is moving.
4. Does the open street map include road location? If so then assume the user has followed the road between two gps points for calculating the speed and distance travelled.
5. The phone app zooms on double tap - it shouldn't.
6. The phone app still pans when you drag - it shouldn't.
7. The values to display should be: state of charge (%), current speed (mph), trip distance (miles), duration of trip (hh:mm:ss).
8. Duration should update every second - but be calculated from the start time, not incremented.
9. Ensure the "distance" is now total trip distance, not distance from last gps point.

- Something so that logs rotate and don't fill up the device storage over time.
- Places to default to nearer to the user's home location.
- the debounce distance could be better - currently it looks at index points on the route - maybe doing a lat/lng calculation would be better.

(1) If I stop navigation in the app, it still seems to consume a lot of battery - possibly just the sdk running in the background, GPS updates etc. Is it possible to have a button to "pause" the app, so it stops all background activity until I press "resume"? (2) The logged vehicle data should be in files of no more than 1 week duration. (3) The logs should rotate so there is a maximum of 4 weeks of logs stored on the device. (4) The battery SOC and temp logging should be increased to every 5 seconds.

-

View logs is laggy if the log is large.
Tidy up README at end
