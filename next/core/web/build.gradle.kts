plugins { kotlin("jvm") }
dependencies {
    api(project(":model"))
    compileOnly("xmlpull:xmlpull:1.1.3.1")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation(project(":testing"))
}
