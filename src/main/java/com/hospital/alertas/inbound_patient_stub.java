    @GET
    @Path("/patients")
    public Response getAllPatients() {
        // Stub patient data - seeds P102, P103 from domain model for local dev and prod fallback
        return Response.ok(
            List.of(new LabResultView("P102", "John Martinez", 45),  
                            new LabResultView("P103", "Maria Santos", 58))    
                   )
                .build();
    }
