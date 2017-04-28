/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package net.jmesnil.microprofile.config.extension;

import static net.jmesnil.microprofile.config.extension.MicroProfileConfigLogger.ROOT_LOGGER;
import static org.jboss.as.controller.SimpleAttributeDefinitionBuilder.create;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MODULE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import net.jmesnil.microprofile.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AbstractRemoveStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.AttributeMarshaller;
import org.jboss.as.controller.AttributeMarshallers;
import org.jboss.as.controller.AttributeParsers;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PersistentResourceDefinition;
import org.jboss.as.controller.PropertiesAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;

/**
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2017 Red Hat inc.
 */
public class ConfigSourceDefinition extends PersistentResourceDefinition {

    static AttributeDefinition ORDINAL = SimpleAttributeDefinitionBuilder.create("ordinal", ModelType.INT)
            .setDefaultValue(new ModelNode(100))
            .setAllowNull(true)
            .setRestartAllServices()
            .build();
    static AttributeDefinition PROPERTIES = new PropertiesAttributeDefinition.Builder("properties", true)
            .setAttributeParser(new AttributeParsers.PropertiesParser(false))
            .setAttributeMarshaller(new AttributeMarshallers.PropertiesAttributeMarshaller(null, false))
            .setAlternatives("class")
            .setAllowNull(true)
            .setRestartAllServices()
            .build();
    static ObjectTypeAttributeDefinition CLASS = ObjectTypeAttributeDefinition.Builder.of("class",
            create(NAME, ModelType.STRING, false)
                    .setAllowExpression(false)
                    .build(),
            create(MODULE, ModelType.STRING, false)
                    .setAllowExpression(false)
                    .build())
            .setAlternatives("properties")
            .setAllowNull(true)
            .setAttributeMarshaller(AttributeMarshaller.ATTRIBUTE_OBJECT)
            .build();

    static AttributeDefinition[] ATTRIBUTES = { ORDINAL, PROPERTIES, CLASS };

    protected ConfigSourceDefinition() {
        super(SubsystemExtension.CONFIG_SOURCE_PATH,
                SubsystemExtension.getResourceDescriptionResolver(SubsystemExtension.CONFIG_SOURCE_PATH.getKey()),
                new AbstractAddStepHandler(ATTRIBUTES) {
                    @Override
                    protected boolean requiresRuntime(OperationContext context) {
                        return true;
                    }

                    @Override
                    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
                        super.performRuntime(context, operation, model);
                        String name = context.getCurrentAddressValue();
                        int ordinal = ORDINAL.resolveModelAttribute(context, model).asInt();
                        ModelNode props = PROPERTIES.resolveModelAttribute(context, model);
                        if (props.isDefined()) {
                            Map<String, String> properties = PropertiesAttributeDefinition.unwrapModel(context, props);
                            ConfigSource configSource = new PropertiesConfigSource(properties, name, ordinal);
                            ConfigSourceService.install(context, name, configSource);
                        }
                        ModelNode classModel = CLASS.resolveModelAttribute(context, model);
                        if (classModel.isDefined()) {
                            System.out.println("classModel = " + classModel);
                            Class configSourceClass = unwrapClass(classModel);
                            System.out.println("configSourceClass = " + configSourceClass);
                            try {
                                ConfigSource configSource = ConfigSource.class.cast(configSourceClass.newInstance());
                                ConfigSourceService.install(context, name, configSource);
                            } catch (Exception e) {
                                throw new OperationFailedException(e);
                            }
                        }
                    }
                }, new AbstractRemoveStepHandler() {
                    @Override
                    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
                        String name = context.getCurrentAddressValue();
                        context.removeService(ConfigSourceService.SERVICE_NAME.append(name));
                    }
                });
    }

    @Override
    public Collection<AttributeDefinition> getAttributes() {
        return Arrays.asList(ATTRIBUTES);
    }

    private static Class unwrapClass(ModelNode classModel) throws OperationFailedException {
        String className = classModel.get(NAME).asString();
        String moduleName = classModel.get(MODULE).asString();
        try {
            ModuleIdentifier moduleID = ModuleIdentifier.fromString(moduleName);
            Module module = Module.getCallerModuleLoader().loadModule(moduleID);
            Class<?> clazz = module.getClassLoader().loadClass(className);
            return clazz;
        } catch (Exception e) {
            throw ROOT_LOGGER.unableToLoadClassFromModule(className, moduleName);
        }
    }
}
