#pragma once

#include <utils/misc.glsl>

#include MATERIAL_COUNT

struct Material {
    int colorMap;
    int normalMap;
    int displacementMap;
    int roughnessMap;
    int ambientOcclusionMap;
    int flowMap;
    int shadowAlphaMap;
    int flags; // overrideBaseColor << 2 | unlit << 1 | hasTransparency
    float subsurface;
    float brightness;
    float displacementScale;
    float specularStrength;
    float specularGloss;
    float flowMapStrength;
    vec2 flowMapDuration;
    vec2 scrollDuration;
    vec3 textureScale;
};

// Zelus: stored packed (see UBOMaterials.MaterialStruct.maps) to fit the 64 KB uniform block limit;
// getMaterial() returns the unpacked Material, so the shader code is unchanged
struct MaterialPacked {
    ivec4 maps; // color|normal, displacement|roughness, ambientOcclusion|flow, shadowAlpha|unused (signed 16-bit)
    int flags;
    float subsurface;
    float brightness;
    float displacementScale;
    float specularStrength;
    float specularGloss;
    float flowMapStrength;
    vec2 flowMapDuration;
    vec2 scrollDuration;
    vec3 textureScale;
};

layout(std140) uniform UBOMaterials {
    MaterialPacked Array[MATERIAL_COUNT];
} uboMaterials;

Material unpackMaterial(MaterialPacked p) {
    Material m;
    m.colorMap = (p.maps.x << 16) >> 16;
    m.normalMap = p.maps.x >> 16;
    m.displacementMap = (p.maps.y << 16) >> 16;
    m.roughnessMap = p.maps.y >> 16;
    m.ambientOcclusionMap = (p.maps.z << 16) >> 16;
    m.flowMap = p.maps.z >> 16;
    m.shadowAlphaMap = (p.maps.w << 16) >> 16;
    m.flags = p.flags;
    m.subsurface = p.subsurface;
    m.brightness = p.brightness;
    m.displacementScale = p.displacementScale;
    m.specularStrength = p.specularStrength;
    m.specularGloss = p.specularGloss;
    m.flowMapStrength = p.flowMapStrength;
    m.flowMapDuration = p.flowMapDuration;
    m.scrollDuration = p.scrollDuration;
    m.textureScale = p.textureScale;
    return m;
}

#include MATERIAL_GETTER

bool getMaterialShouldOverrideBaseColor(const Material material) {
    return (material.flags >> 2 & 1) == 1;
}

int getMaterialIsUnlit(const Material material) {
    return material.flags >> 1 & 1;
}

bool getMaterialHasTransparency(const Material material) {
    return (material.flags & 1) == 1;
}
