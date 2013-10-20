<?php

include("dbconnect.php");

$username = "uakf.b";
$sessionkey = rand(0, 4294967295); //random uint32
$proxy = "";
$startalert = true;

$result = databaseQuery("SELECT id, proxy, startalert FROM wc3connect WHERE username = ?", array($username));

if($row = $result->fetch()) {
	$wc_id = $row[0];
	$proxy = $row[1];
	$startalert = $row[2] == 0 ? false : true;

	databaseQuery("UPDATE wc3connect SET sessionkey = ?, time = NOW() WHERE id = ?", array($sessionkey, $wc_id));
} else {
	$result = databaseQuery("INSERT INTO wc3connect (username, sessionkey, time, proxy) VALUES (?, ?, NOW(), '')", array($username, $sessionkey));
	$wc_id = $result->lastInsertId();
}

$listpath = "https://yourdomain.com/wc3connect/list.php";
$filter = "";
$preferred = "6117, 6118"; //comma-separated list of port numbers that are marked as "preferred"; leave blank to disable

if(isset($_GET['filter'])) {
	$filter = htmlspecialchars($_GET['filter']);
}

if(isset($_POST['proxy'])) {
	$proxy = htmlspecialchars($_POST['proxy']);
	$startalert = isset($_POST['startalert']) ? 1 : 0;

	//permanently update settings
	databaseQuery("UPDATE wc3connect SET proxy = ?, startalert = ? WHERE id = ?", array($proxy, $startalert, $wc_id));
}

$template->assign_vars(array('SESSIONKEY' => $sessionkey,
                             'LISTPATH' => $listpath,
                             'ECUSERNAME' => $username_clean,
                             'FILTER' => $filter,
                             'PROXY' => $proxy,
                             'PREFERRED' => $preferred,
                             'WAR3PATH' => $war3path,
                             'STARTALERT' => $startalert ? "true" : "false",
                             'STARTALERT_ENABLED' => $startalert));

?>

<html>
<body>
<h1>WC3Connect</h1>

<form method="POST">
<p>Proxy setting: <select name="proxy">
<? if(!empty($proxy)) { ?><option value="<?= $proxy ?>"><?= $proxy ?></option><? } ?>
<option value="" <? if(empty($proxy)) { ?>selected<? } ?>>Disable</option>
<option value="proxy.yourdomain.com:1212">Proxy 1</option>
</select>
<br /><input type="checkbox" name="startalert" value="true" <? if($startalert) { ?>checked<? } ?>/> Play sound notification when the game starts
<br /><input type="submit" value="Update preferences" /></p>
</form>

<APPLET CODE="net/entgaming/wc3connect/WC3Connect.class" ARCHIVE="SWC3Connect.jar" height="30" width="30">
<param name="connection_sessionkey" value="<?= $sessionkey ?>"/>
<param name="list_server" value="<?= $listpath ?>"/>
<param name="connection_username" value="<?= $username ?>"/>
<param name="list_filter" value="<?= $filter ?>"/>
<param name="connection_proxy" value="<?= $proxy ?>"/>
<param name="list_preferred" value="<?= $preferred ?>"/>
<param name="startalert" value="<?= $startalert ? "true" : "false" ?>"/>
<param name="log_target" value="http://yourdomain.com/wc3connect/log.php"/>
<h1>You must have Java enabled to use WC3Connect.<br />
To download Java, <a href="http://java.com/en/download/index.jsp">click here</a>.</h1>
</APPLET>

<h1>WC3Connect</h1>
<p><b>Usage</b>: If this is your first time running WC3Connect, a window will popup asking for permission. Check "Always trust content from this publisher" (this is optional, but if you do not then the window will popup every time), and then click Run. The square should then turn cyan and then green. Make sure that you have Java 5.0 or higher if this does not happen.</p>

<p>Then, open Warcraft III and select Local Area Network in the main game menu. There, a list of games should come up. To join, make sure that the LAN username is set to your forum username, and double click on a game.</p>
<p>If you reached this page from the gamelist, then the game that you selected should be the first game that comes up in the Local Area Network screen.</p>
<p><b>Do not close this page or you will disconnect from the game!</b></p>

</body>
</html>
