<hr>
<%
	java.util.jar.Manifest manifest = new java.util.jar.Manifest();
	manifest.read(pageContext.getServletContext().getResourceAsStream("/META-INF/MANIFEST.MF"));
	java.util.jar.Attributes attributes = manifest.getMainAttributes();
%>
<small>
	<%=attributes.getValue("Implementation-Title")%><br/>
	Version: <%=attributes.getValue("Implementation-Version")%><br/>
	Build-Time: <%=attributes.getValue("Build-Time")%><br/>
	Build-Jdk: <%=attributes.getValue("Build-Jdk")%>
</small>
