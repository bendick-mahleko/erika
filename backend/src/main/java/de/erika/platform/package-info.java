/**
 * Shared technical infrastructure: job queue, object storage, web conventions,
 * health. Contains no business logic. Open so that every module can use it.
 */
@ApplicationModule(displayName = "Platform", type = ApplicationModule.Type.OPEN)
package de.erika.platform;

import org.springframework.modulith.ApplicationModule;
