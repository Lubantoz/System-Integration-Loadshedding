package wethinkcode.web;

import com.google.common.annotations.VisibleForTesting;
import io.javalin.Javalin;
import io.javalin.http.Context;
import kong.unirest.HttpResponse;
import io.javalin.http.HttpStatus;
import kong.unirest.Unirest;
import wethinkcode.loadshed.common.transfer.ScheduleDO;
import wethinkcode.loadshed.common.transfer.StageDO;
import wethinkcode.loadshed.spikes.MqTopicReceiver;
import wethinkcode.places.PlaceNameService;
import wethinkcode.schedule.ScheduleService;
import wethinkcode.stage.StageService;

import java.util.HashMap;
import java.util.Map;

/**
 * I am the front-end web server for the LightSched project.
 * <p>
 * Remember that we're not terribly interested in the web front-end part of this server, more in the way it communicates
 * and interacts with the back-end services.
 */
public class WebService
{

    public static final int DEFAULT_PORT = 8080;

    public static final String STAGE_SVC_URL = "http://localhost:" + StageService.DEFAULT_PORT;

    public static final String PLACES_SVC_URL = "http://localhost:" + PlaceNameService.DEFAULT_PORT;

    public static final String SCHEDULE_SVC_URL = "http://localhost:" + ScheduleService.DEFAULT_PORT;

    private static final String PAGES_DIR = "/html";

    public static void main( String[] args ){
        final WebService svc = new WebService().initialise();
        svc.start();
    }

    private Javalin server;

    private int servicePort;
    private MqTopicReceiver topicReceiver;

    @VisibleForTesting
    WebService initialise(){
        configureHttpServer();
        configureMessageQueue();
        return this;
    }
    public void start(){
        start( DEFAULT_PORT );
    }

    @VisibleForTesting
    void start( int networkPort ){
        servicePort = networkPort;
        run();
    }

    public void stop(){
        server.stop();
        if (topicReceiver != null) {
            topicReceiver.shutdown();
        }
    }

    public void run(){
        server.start(servicePort);
        System.out.println("WebService started and listening on port " + servicePort);
        System.out.println("Open http://localhost:" + servicePort + " in your browser.");
    }

    private void configureHttpClient(){
        throw new UnsupportedOperationException( "TODO" );
    }

    private void configureMessageQueue() {
        topicReceiver = new MqTopicReceiver();
        try {
            topicReceiver.run();
        } catch (RuntimeException ex) {
            System.err.println("WARN: Failed to start MQ listener: " + ex.getMessage());
            if (ex.getCause() != null) System.err.println("Cause: " + ex.getCause().getMessage());
        }
    }
    private void configureHttpServer(){
        server = Javalin.create();
        server.get("/", this::renderIndex);
        server.get("/{province}/{town}", this::getScheduleForPlace);
    }
    private void renderIndex(Context ctx) {

        int stageValue = 0;
        System.out.println("Fetching stage from " + STAGE_SVC_URL + "/stage");
        try {
            HttpResponse<StageDO> response = Unirest.get(STAGE_SVC_URL + "/stage")
                    .asObject(StageDO.class);
            System.out.println("StageService responded: " + response.getStatus());
            if (response.isSuccess() && response.getBody() != null) {
                stageValue = response.getBody().getStage();
            }
        } catch (Exception ex) {
            System.err.println("ERROR: Failed to fetch stage: " + ex.getMessage());
        }
        
        // Fetch provinces
        String provincesJson = "[]";
        System.out.println("Fetching provinces from " + PLACES_SVC_URL + "/provinces");
        try {
            HttpResponse<String> response = Unirest.get(PLACES_SVC_URL + "/provinces").asString();
            System.out.println("PlacesService responded: " + response.getStatus());
            if (response.isSuccess() && response.getBody() != null) {
                provincesJson = response.getBody();
            }
        } catch (Exception ex) {
            System.err.println("ERROR: Failed to fetch provinces: " + ex.getMessage());
        }
        
        // Build HTML with interactive dropdowns
        String html = "<!DOCTYPE html><html><head><title>Loadshedding Schedule</title>"
            + "<style>"
            + "body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }"
            + "h1 { color: #333; }"
            + ".container { max-width: 600px; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }"
            + ".status { font-size: 18px; font-weight: bold; color: #d9534f; margin: 20px 0; }"
            + ".form-group { margin: 15px 0; }"
            + "label { display: block; margin-bottom: 5px; font-weight: bold; color: #555; }"
            + "select { width: 100%; padding: 8px; font-size: 14px; border: 1px solid #ddd; border-radius: 4px; }"
            + ".schedule { margin-top: 30px; padding: 15px; background: #f9f9f9; border-left: 4px solid #5cb85c; }"
            + ".schedule h3 { margin-top: 0; color: #333; }"
            + "pre { background: white; padding: 10px; border-radius: 4px; overflow-x: auto; font-size: 12px; }"
            + ".loading { color: #999; font-style: italic; }"
            + "</style>"
            + "</head><body>"
            + "<div class='container'>"
            + "<h1>Loadshedding Schedule Finder</h1>"
            + "<div class='status'>Current Stage: <strong id='currentStage'>" + stageValue + "</strong></div>"
            + "<form id='scheduleForm'>"
            + "  <div class='form-group'>"
            + "    <label for='province'>Select Province:</label>"
            + "    <select id='province' name='province' onchange='onProvinceChange()'>"
            + "      <option value=''>-- Choose a province --</option>"
            + "    </select>"
            + "  </div>"
            + "  <div class='form-group'>"
            + "    <label for='town'>Select Town:</label>"
            + "    <select id='town' name='town' onchange='onTownChange()' disabled>"
            + "      <option value=''>-- Choose a town --</option>"
            + "    </select>"
            + "  </div>"
            + "</form>"
            + "<div id='scheduleContainer'></div>"
            + "</div>"
            + "<script>"
            + "const PLACES_URL = '" + PLACES_SVC_URL + "';"
            + "const SCHEDULE_URL = '" + SCHEDULE_SVC_URL + "';"
            + "const STAGE_URL = '" + STAGE_SVC_URL + "';"
            + "let currentStage = " + stageValue + ";"
            + "const provinces = " + provincesJson + ";"
            + ""
            + "function updateStageDisplay() {"
            + "  fetch(STAGE_URL + '/stage')"
            + "    .then(r => r.json())"
            + "    .then(data => {"
            + "      const newStage = data.stage;"
            + "      if (newStage !== currentStage) {"
            + "        currentStage = newStage;"
            + "        document.getElementById('currentStage').textContent = currentStage;"
            + "        if (document.getElementById('town').value) {"
            + "          onTownChange();"
            + "        }"
            + "      }"
            + "    })"
            + "    .catch(e => console.error('Failed to update stage:', e));"
            + "}"
            + ""
            + "function initProvinces() {"
            + "  const select = document.getElementById('province');"
            + "  select.innerHTML = '<option value=\"\">-- Choose a province --</option>';"
            + "  provinces.forEach(pRaw => {"
            + "    const p = (typeof pRaw === 'string') ? pRaw.trim() : (pRaw && pRaw.name ? pRaw.name : JSON.stringify(pRaw));"
            + "    if (!p) return;"
            + "    const option = document.createElement('option');"
            + "    option.value = p;"
            + "    option.textContent = p;"
            + "    select.appendChild(option);"
            + "  });"
            + "}"
            + ""
            + "function onProvinceChange() {"
            + "  const province = document.getElementById('province').value;"
            + "  const townSelect = document.getElementById('town');"
            + "  townSelect.innerHTML = '<option value=\"\">-- Loading towns --</option>';"
            + "  townSelect.disabled = true;"
            + "  document.getElementById('scheduleContainer').innerHTML = '';"
            + "  if (!province) { townSelect.innerHTML = '<option value=\"\">-- Choose a town --</option>'; return; }"
            + "  fetch(PLACES_URL + '/towns/' + encodeURIComponent(province))"
            + "    .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })"
            + "    .then(towns => {"
            + "      townSelect.innerHTML = '<option value=\"\">-- Choose a town --</option>';"
            + "      towns.forEach(t => {"
            + "        const name = (typeof t === 'string') ? t.trim() : (t && t.name ? t.name : JSON.stringify(t));"
            + "        const option = document.createElement('option');"
            + "        option.value = name;"
            + "        option.textContent = name;"
            + "        townSelect.appendChild(option);"
            + "      });"
            + "      townSelect.disabled = false;"
            + "    })"
            + "    .catch(e => {"
            + "      townSelect.innerHTML = '<option value=\"\">Error loading towns</option>';"
            + "      console.error(e);"
            + "    });"
            + "}"
            + ""
            + "function formatTime(timeArray) {"
            + "  const [hours, minutes] = timeArray;"
            + "  return ('0' + hours).slice(-2) + ':' + ('0' + minutes).slice(-2);"
            + "}"
            + ""
            + "function onTownChange() {"
            + "  const province = document.getElementById('province').value;"
            + "  const town = document.getElementById('town').value;"
            + "  const container = document.getElementById('scheduleContainer');"
            + "  if (!province || !town) return;"
            + "  container.innerHTML = '<div class=\"schedule\"><p class=\"loading\">Loading schedule...</p></div>';"
            + "  fetch(SCHEDULE_URL + '/' + encodeURIComponent(province) + '/' + encodeURIComponent(town) + '/' + currentStage)"
            + "    .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })"
            + "    .then(schedule => {"
            + "      let html = '<div class=\"schedule\">';"
            + "      html += '<h3>Loadshedding Schedule for ' + town + ', ' + province + ' (Stage ' + currentStage + ')</h3>';"
            + "      html += '<table style=\"width:100%;border-collapse:collapse;\">';"
            + "      html += '<tr style=\"background:#e8f4f8;\"><th style=\"padding:10px;text-align:left;border:1px solid #ddd;\">Day</th><th style=\"padding:10px;text-align:left;border:1px solid #ddd;\">Loadshedding Times</th></tr>';"
            + "      const dayNames = ['Day 1', 'Day 2', 'Day 3', 'Day 4'];"
            + "      schedule.days.forEach((day, dayIndex) => {"
            + "        const times = day.slots.map(slot => formatTime(slot.start) + ' - ' + formatTime(slot.end)).join(', ');"
            + "        const bgColor = dayIndex % 2 === 0 ? '#f9f9f9' : 'white';"
            + "        html += '<tr style=\"background:' + bgColor + ';\">';"
            + "        html += '<td style=\"padding:10px;border:1px solid #ddd;font-weight:bold;\">' + dayNames[dayIndex] + '</td>';"
            + "        html += '<td style=\"padding:10px;border:1px solid #ddd;\">' + times + '</td>';"
            + "        html += '</tr>';"
            + "      });"
            + "      html += '</table>';"
            + "      html += '</div>';"
            + "      container.innerHTML = html;"
            + "    })"
            + "    .catch(e => {"
            + "      container.innerHTML = '<div class=\"schedule\"><p style=\"color: red;\">Error loading schedule: ' + e.message + '</p></div>';"
            + "      console.error(e);"
            + "    });"
            + "}"
            + ""
            + "window.addEventListener('load', () => {"
            + "  initProvinces();"
            + "  updateStageDisplay();"
            + "  setInterval(updateStageDisplay, 5000);"
            + "});"
            + "</script>"
            + "</body></html>";
        
        ctx.html(html);
    }
    private void getScheduleForPlace(Context ctx) {
        final String province = ctx.pathParam("province");
        final String town = ctx.pathParam("town");

        if (province.isEmpty() || town.isEmpty()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            return;
        }
        try {
            HttpResponse<ScheduleDO> response = Unirest.get(SCHEDULE_SVC_URL + "/{province}/{town}")
                    .routeParam("province", province)
                    .routeParam("town", town)
                    .asObject(ScheduleDO.class);

            if (response.isSuccess() && response.getBody() != null) {
                ctx.json(response.getBody());
            } else {
                ctx.status(response.getStatus());
                ctx.result("Schedule not found");
            }
        } catch (Exception ex) {
            ctx.status(500);
            ctx.result("Error fetching schedule: " + ex.getMessage());
        }
    }
}
