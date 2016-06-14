var api = function() {

  var getToken = function() {
    return firebase.auth().currentUser.getToken();
  }

  var getUserId = function() {
    return firebase.auth().currentUser.uid;
  }

  var callEndpoint = function(method, uri, jsonData, onload, onerror) {
      getToken().then(function(token) {
        var xmlhttp = new XMLHttpRequest();
        xmlhttp.onload = function(xmlEvent) {
          console.log(xmlEvent);
          console.log(xmlhttp.response);
          if (onload) {
            onload(xmlEvent);
          }
        }
        xmlhttp.onerror = function() {
          console.log("Error: " + xmlhttp.response);
          if (onerror) {
            onerror(xmlhttp.response);
          }
        }
        xmlhttp.open(method, "/api/v1" + uri);
        xmlhttp.setRequestHeader("Content-Type", "application/json");
        xmlhttp.setRequestHeader("Authorization", token);
        xmlhttp.send(JSON.stringify(jsonData));
      });
  }

  return {
    subscribeToChannel: function(channelId, onload, onerror) {
      callEndpoint(
        "POST", 
        "/channels/" + channelId + "/subscribers",
        {"user-id": getUserId()},
        onload,
        onerror
      );
    },
    sendEventToChannel: function(channelId, title, body, onload, onerror) {
      callEndpoint(
        "POST",
        "/channels/" + channelId + "/events",
        {"title": title, "body": body},
        onload,
        onerror
      );
    },
  };
}();
