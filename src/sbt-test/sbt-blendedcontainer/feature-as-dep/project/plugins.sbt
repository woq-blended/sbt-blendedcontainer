(
  sys.props.get("plugin.org"),
  sys.props.get("plugin.name"),
  sys.props.get("plugin.version")
) match {
  case (Some(org), Some(name), Some(version)) => addSbtPlugin(org % name % version)
  case _ => sys.error("""|The system properties 'plugin.org', 'plugin.name' and 'plugin.version' must be defined for scripted tests.
                         |Specify these property using the scriptedLaunchOpts -D.""".stripMargin)
}

